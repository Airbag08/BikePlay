# BikePlay: Interactive Bike Dashboard

BikePlay is a distributed IoT and Android architecture designed to provide real-time biking data. The system aggregates data from three distinct nodes into a single CarPlay-inspired interface:
1. **ESP32 Sensor Node:** Hardware interrupt-driven speed tracking using an A3144 digital Hall effect sensor, and real-time incline calculation using an MPU6050 6-DoF IMU over I2C.
2. **Wear OS Node:** Live heart rate monitoring utilizing the smartwatch's optical PPG sensor via Google Health Services.
3. **Android Dashboard:** The central hub that receives BLE data, calculates real-time caloric burn using the Keytel formula, tracks distance via GPS, controls media playback, and renders post-ride summary charts.

This guide details the exact steps required to install dependencies, wire the hardware, configure your environments, and flash the software to each node.

---

## Prerequisites

* **Software:**
    * [Android Studio](https://developer.android.com/studio) (Latest Version recommended)
    * [Arduino IDE](https://www.arduino.cc/en/software) (v2.0+)
* **Hardware:**
    * Android Smartphone (Android 12+ recommended for updated BLE permissions)
    * Wear OS Smartwatch (Wear OS 3.0+)
    * ESP32 Development Board
    * MPU6050 Accelerometer/Gyroscope Module
    * A3144 Digital Hall Effect Sensor & Spoke Magnet

---

## Part 1: ESP32 Hardware & Firmware Setup

### 1. Arduino IDE Configuration
To program the ESP32, you must add the Espressif Board Support Package to the Arduino IDE:
1. Open Arduino IDE -> **File** -> **Preferences**.
2. In the "Additional Boards Manager URLs" field, paste:
   `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
3. Go to **Tools** -> **Board** -> **Boards Manager**, search for `esp32` by Espressif Systems, and install it.
4. Go to **Sketch** -> **Include Library** -> **Manage Libraries**, and install the **Adafruit MPU6050** library (and its dependencies).

### 2. Wiring the Sensors
**A3144 Digital Hall Sensor (Speed):**
The A3144 is an open-collector digital sensor. The code utilizes the ESP32's internal pull-up resistor, so no external resistors are required.
* **Pin 1 (VCC, Left):** Connect to ESP32 **3.3V**.
* **Pin 2 (GND, Center):** Connect to ESP32 **GND**.
* **Pin 3 (OUT, Right):** Connect to ESP32 **GPIO 23**.

**MPU6050 IMU (Incline):**
The MPU6050 communicates via the I2C protocol.
* **VCC:** Connect to ESP32 **3.3V**.
* **GND:** Connect to ESP32 **GND**.
* **SDA:** Connect to ESP32 **GPIO 21** (Default I2C Data).
* **SCL:** Connect to ESP32 **GPIO 22** (Default I2C Clock).

### 3. Flashing the Firmware
1. Connect the ESP32 to your computer via a data-capable USB cable.
2. Select the correct COM port in Arduino IDE (**Tools** -> **Port**).
3. Open your ESP32 sketch.
4. Click **Upload**. *(Note: If the console hangs on "Connecting...", hold the physical `BOOT` button on the ESP32 until the flashing percentage begins).*

---

## Part 2: Android Dashboard Setup (Phone)

### 1. Project Configuration (JitPack)
The ride summary uses the `MPAndroidChart` library, which requires the JitPack repository.
Open your **project-level** `settings.gradle` file and ensure the `dependencyResolutionManagement` block looks like this:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url '[https://jitpack.io](https://jitpack.io)' } // Required for MPAndroidChart
    }
}
```

### 2. App Dependencies
Open your app-level build.gradle (usually `app/build.gradle`) and add these specific libraries to the dependencies block:
```gradle
dependencies {
    // Google Location Services (For GPS Speed & Distance)
    implementation 'com.google.android.gms:play-services-location:21.0.1'

    // Wearable Data Layer (To receive Heart Rate from the watch)
    implementation 'com.google.android.gms:play-services-wearable:18.1.0'

    // MPAndroidChart (For the Ride Summary Graph)
    implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'
}
```
Click Sync Now in the top right banner of Android Studio.

### 3. Manifest Permissions
Ensure your phone's `AndroidManifest.xml` includes the following permissions:
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```
## Part 3: Wear OS Watch Setup
If your watch module is located in the same Android Studio project, open the `wear/build.gradle` file.

### 1. Watch Dependencies
Add the Health Services and Guava dependencies:
```gradle
dependencies {
    // Google Health Services (To read the optical Heart Rate sensor)
    implementation 'androidx.health:health-services-client:1.0.0-rc01'

    // Wearable Data Layer (To send the BPM payload to the phone)
    implementation 'com.google.android.gms:play-services-wearable:18.1.0'
    
    // Guava (Required by Health Services for async callbacks)
    implementation 'com.google.guava:guava:31.1-android'
}
```
Click Sync Now.

### 2. Watch Manifest Permissions
Ensure the Wear module's `AndroidManifest.xml` includes:
```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
## Running the System
### Mount the Hardware: 
Secure the ESP32 and MPU6050 to the bike frame. Ensure the MPU6050 is mounted perfectly level to the ground for accurate base incline readings. Align the A3144 sensor on the fork so the spoke magnet passes within ~5mm of the flat face.

### Start the Watch Sensor: 
Open the BikePlay Wear OS app on your smartwatch. Grant the Body Sensors permission when prompted. The screen will display "Service Running" when heart rate data begins streaming.

### Launch the Dashboard:
Open the Android app on your phone.

### Notification Access: 
The app will prompt you for Notification Access. Grant it so the MediaNotificationService can read the currently playing track and track duration.

### Connect BLE: 
Tap the green "Connect" button at the top of the dashboard, grant Location/Bluetooth permissions if prompted, and select your ESP32 from the list.

### Ride: 
Begin pedaling. Heart rate will stream from your wrist; incline will update from the MPU6050; speed will trigger from the A3144 and GPS; and media can be controlled directly from the CarPlay-style dashboard.

### Finish:
Tap "Stop Ride" to halt the session and generate the MPAndroidChart ride summary graph.
