package de.kai_morich.simple_bluetooth_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.health.services.client.HealthServices;
import androidx.health.services.client.HealthServicesClient;
import androidx.health.services.client.MeasureCallback;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.DataTypeAvailability;
import androidx.health.services.client.data.DeltaDataType;
import androidx.health.services.client.data.SampleDataPoint;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class HeartRateService extends Service {

    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "hr_channel";

    private MeasureClient measureClient;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private boolean callbackRegistered = false;

    private final MeasureCallback heartRateCallback = new MeasureCallback() {
        @Override
        public void onAvailabilityChanged(DeltaDataType<?, ?> dataType, Availability availability) {
            Log.d(TAG, "Availability changed: " + availability);
            if (availability == DataTypeAvailability.AVAILABLE) {
                Log.d(TAG, "Sensor is AVAILABLE — data should start flowing");
            } else {
                Log.w(TAG, "Sensor NOT available: " + availability);
            }
        }

        @Override
        public void onDataReceived(DataPointContainer data) {
            Log.d(TAG, "onDataReceived called");
            List<SampleDataPoint<Double>> points = data.getData(DataType.HEART_RATE_BPM);
            Log.d(TAG, "Points received: " + points.size());
            for (SampleDataPoint<Double> point : points) {
                double bpm = point.getValue();
                Log.d(TAG, "Heart rate: " + bpm + " bpm");
                sendToPhone(bpm);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");

        try {
            // Android 14+ Requires the foreground type to be explicitly stated in the code
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(1, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
            } else {
                startForeground(1, buildNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "Foreground start failed: " + e.getMessage());
        }

        HealthServicesClient healthClient = HealthServices.getClient(this);
        measureClient = healthClient.getMeasureClient();
        checkCapabilityAndRegister();
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // This tells the Android OS to restart the service if it gets killed for memory reasons
        return START_STICKY;
    }

    private void checkCapabilityAndRegister() {
        ListenableFuture<androidx.health.services.client.data.MeasureCapabilities> future =
                measureClient.getCapabilitiesAsync();

        Futures.addCallback(future, new FutureCallback<androidx.health.services.client.data.MeasureCapabilities>() {
            @Override
            public void onSuccess(androidx.health.services.client.data.MeasureCapabilities capabilities) {
                if (capabilities.getSupportedDataTypesMeasure().contains(DataType.HEART_RATE_BPM)) {
                    Log.d(TAG, "HEART_RATE_BPM supported — registering callback");
                    registerCallback();
                } else {
                    Log.e(TAG, "HEART_RATE_BPM NOT supported on this device");
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Failed to get capabilities: " + t.getMessage());
                // Try registering anyway
                registerCallback();
            }
        }, executor);
    }

    private void registerCallback() {
        try {
            measureClient.registerMeasureCallback(
                    DataType.HEART_RATE_BPM,
                    executor,
                    heartRateCallback
            );
            callbackRegistered = true;
            Log.d(TAG, "Callback registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register callback: " + e.getMessage());
        }
    }

    private void sendToPhone(double bpm) {
        executor.execute(() -> {
            try {
                List<Node> nodes = Tasks.await(
                        Wearable.getNodeClient(this).getConnectedNodes()
                );

                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected nodes found — is phone paired?");
                    return;
                }

                byte[] payload = String.valueOf(bpm).getBytes();
                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(this)
                            .sendMessage(node.getId(), "/heart_rate", payload));
                    Log.d(TAG, "Sent " + bpm + " bpm to: " + node.getDisplayName());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send HR: " + e.getMessage());
            }
        });
    }

    private Notification buildNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Heart Rate", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        // Dynamically grab the app's own icon to prevent a ResourceNotFoundException
        int iconId = getApplicationInfo().icon != 0 ? getApplicationInfo().icon : android.R.drawable.ic_dialog_info;

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Heart Rate Active")
                .setContentText("Sending HR to phone")
                .setSmallIcon(iconId)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        callbackRegistered = false;
        Log.d(TAG, "Service destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}