#include <Wire.h>
#include <MPU6050.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>


#define SERVICE_UUID   "12345678-1234-1234-1234-123456789abc"
#define IMU_CHAR_UUID  "12345678-1234-1234-1234-123456789abd"
#define HALL_CHAR_UUID "12345678-1234-1234-1234-123456789abe"


#define HALL_PIN             23      // GPIO 23 (USE AN INPUT ONLY PIN IF USING TRUE ANALOG SENSOR)
#define WHEEL_DIAMETER_IN  24.0f     // your wheel diameter in inches
#define MAGNETS_PER_ROT      2       // 2 magnet on wheel
#define HALL_THRESHOLD    2000       // ADC crossover point (0–4095)
#define PULSE_TIMEOUT_MS  3000       // speed = 0 if no pulse for this long

const float WHEEL_CIRC_MILES = (WHEEL_DIAMETER_IN * PI) / (12.0f * 5280.0f);

#define KEEP_ALIVE_PIN 32 // Modify if needed
unsigned long lastPulseTime = 0;


MPU6050 mpu;
float pitchOffset = 0.0f; // Needed for calibration


bool          lastHallState  = false;
unsigned long lastPulseUs    = 0;   
unsigned long pulseIntervalUs = 0;


BLEServer* pServer   = nullptr;
BLECharacteristic* pImuChar  = nullptr;
BLECharacteristic* pHallChar = nullptr;
bool deviceConnected = false;

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        deviceConnected = true;
        Serial.println("[BLE] Client connected");
    }
    void onDisconnect(BLEServer* pServer) override {
        deviceConnected = false;
        Serial.println("[BLE] Client disconnected — restarting advertising");
        BLEDevice::startAdvertising();
    }
};


void setup() {
    Serial.begin(115200);

    //Zeroing MPU6050
    Wire.begin();           
    mpu.initialize();

    if (mpu.testConnection()) {
        Serial.println("[MPU6050] Connected OK. Calibrating...");
        delay(500); // Let sensor settle

        float sumPitch = 0.0f;
        int samples = 50;
        for (int i = 0; i < samples; i++) {
            int16_t ax, ay, az, gx, gy, gz;
            mpu.getMotion6(&ax, &ay, &az, &gx, &gy, &gz);
            float axG = ax / 16384.0f;
            float ayG = ay / 16384.0f;
            float azG = az / 16384.0f;
            sumPitch += atan2(axG, sqrt(ayG * ayG + azG * azG)) * 180.0f / PI;
            delay(10);
        }
        pitchOffset = sumPitch / (float)samples;
        Serial.printf("Calibrated! Zero offset is: %.2f degrees\n", pitchOffset);
        
    } else {
        Serial.println("[MPU6050] Connection FAILED — check wiring");
    }

    //Initialize Hall pin
    pinMode(HALL_PIN, INPUT);

    
    BLEDevice::init("ESP32-BT-Slave");   
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pImuChar = pService->createCharacteristic(IMU_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
    pImuChar->addDescriptor(new BLE2902());

    pHallChar = pService->createCharacteristic(HALL_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
    pHallChar->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->start();

    Serial.println("[BLE] Advertising as 'ESP32-BT-Slave'");
    pinMode(KEEP_ALIVE_PIN, OUTPUT);
}


bool checkHallPulse() {
    int  raw          = analogRead(HALL_PIN);
    bool currentState = (raw > HALL_THRESHOLD);

    if (currentState && !lastHallState) {
        unsigned long now = micros();
        if (lastPulseUs > 0) {
            pulseIntervalUs = now - lastPulseUs;
        }
        lastPulseUs = now;
        lastHallState = true;
        return true;
    }

    if (!currentState) {
        lastHallState = false;
    }
    return false;
}


float calcMph() {
    unsigned long nowMs = millis();
    unsigned long lastPulseMs = lastPulseUs / 1000UL;
    
    if (lastPulseUs == 0 || (nowMs - lastPulseMs) > PULSE_TIMEOUT_MS) return 0.0f;
    if (pulseIntervalUs == 0) return 0.0f;

    float intervalSec     = pulseIntervalUs / 1000000.0f;
    float rotationsPerSec = 1.0f / (intervalSec * MAGNETS_PER_ROT);
    return rotationsPerSec * WHEEL_CIRC_MILES * 3600.0f;
}


unsigned long lastSendMs = 0;
const unsigned long SEND_INTERVAL_MS = 200; 

void loop() {
    // Poll hall sensor quickly
    checkHallPulse();

    unsigned long now = millis();
    if (now - lastSendMs < SEND_INTERVAL_MS) return;
    lastSendMs = now;

    if (!deviceConnected) return;

    //Zero the incline
    int16_t ax, ay, az, gx, gy, gz;
    mpu.getMotion6(&ax, &ay, &az, &gx, &gy, &gz);
    float axG = ax / 16384.0f;
    float ayG = ay / 16384.0f;
    float azG = az / 16384.0f;
    
    float rawPitch = atan2(axG, sqrt(ayG * ayG + azG * azG)) * 180.0f / PI;
    float finalPitch = -1* (rawPitch - pitchOffset);

    
    char imuBuf[16];
    snprintf(imuBuf, sizeof(imuBuf), "%.1f", finalPitch);
    pImuChar->setValue(imuBuf);
    pImuChar->notify();

    
    float mph = calcMph();
    char hallBuf[16];
    snprintf(hallBuf, sizeof(hallBuf), "%.2f", mph);
    pHallChar->setValue(hallBuf);
    pHallChar->notify();

    
    Serial.printf("[IMU] pitch=%.1f° | [HALL] speed=%.2f mph\n", finalPitch, mph);


    if (now - lastPulseTime > 10000) {
        digitalWrite(KEEP_ALIVE_PIN, HIGH);
        delay(200); 
        digitalWrite(KEEP_ALIVE_PIN, LOW);
        lastPulseTime = now;
    }
}