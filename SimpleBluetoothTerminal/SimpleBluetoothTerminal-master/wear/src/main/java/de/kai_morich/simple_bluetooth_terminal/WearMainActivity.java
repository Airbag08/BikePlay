package de.kai_morich.simple_bluetooth_terminal;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class WearMainActivity extends ComponentActivity {

    private TextView statusText;
    private Button actionButton;


    private final ActivityResultLauncher<String> sensorLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), result -> {
                evaluatePermissions();
            });


    private final ActivityResultLauncher<String> notifLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), result -> {
                evaluatePermissions();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_main);

        statusText = findViewById(R.id.tv_status);
        actionButton = findViewById(R.id.btn_grant);

        evaluatePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        evaluatePermissions();
    }

    private void evaluatePermissions() {
        boolean hasSensors = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
        boolean hasNotifs = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;


        String debugStr = "OS Status -> Sensors: " + (hasSensors ? "OK" : "FAIL") + " | Notifs: " + (hasNotifs ? "OK" : "FAIL");

        if (!hasSensors) {
            statusText.setText(debugStr + "\n\nWaiting on Sensors...");
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText("Request Sensors");
            actionButton.setOnClickListener(v -> sensorLauncher.launch(Manifest.permission.BODY_SENSORS));

        } else if (!hasNotifs) {
            statusText.setText(debugStr + "\n\nWaiting on Notifications...");
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText("Request Notifs");
            actionButton.setOnClickListener(v -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS));

        } else {
            actionButton.setVisibility(View.GONE);
            startHrService();
        }
    }

    private void startHrService() {
        try {
            startForegroundService(new Intent(this, HeartRateService.class));
            statusText.setText("Service Running\nWaiting for pulse...");
            statusText.setTextColor(Color.GREEN);
        } catch (Exception e) {
            statusText.setText("Service Crash: " + e.getMessage());
            statusText.setTextColor(Color.RED);
        }
    }
}