package de.kai_morich.simple_bluetooth_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import android.annotation.SuppressLint;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {


    private enum Connected { False, Pending, True }
    private TextView tvTrackInfo;
    private ImageView ivAlbumArt;
    private String deviceAddress;
    private SerialService service;
    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;


    private TextView receiveText, sendText;
    private TextUtil.HexWatcher hexWatcher;
    private TextView tvHeartRate, tvAccel, tvHall, tvDistance, tvEspStatus, tvCalories; // <-- Added tvCalories here


    private static final String ESP_SERVICE_UUID = "12345678-1234-1234-1234-123456789abc";
    private static final String IMU_CHAR_UUID    = "12345678-1234-1234-1234-123456789abd";
    private static final String CCCD_UUID        = "00002902-0000-1000-8000-00805f9b34fb";

    private BluetoothLeScanner bleScanner;
    private BluetoothGatt espGatt;
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<BluetoothGattCharacteristic> pendingNotifications = new ArrayList<>();


    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private float totalDistanceMiles = 0.0f;
    private long lastSpeedUpdateMs = 0;
    private float currentSpeedMph = 0f;
    private float currentIncline = 0f;

    private double totalCalories = 0.0;
    private long lastCalorieUpdateMs = 0;

    // PERSON SPECIFIC METRICS - CHANGE
    private final int AGE = 22;
    private final double WEIGHT_KG = 49.2;
    private final boolean IS_MALE = false;

    // Ride Summary Variables
    private final List<RideDataPoint> rideHistory = new ArrayList<>();
    private long rideStartTimeMs = 0;
    private float maxSpeed = 0f;
    private float maxIncline = 0f;

    private static class RideDataPoint {
        float timeElapsedMinutes;
        float speedMph;
        float inclineDegrees;
        RideDataPoint(float timeElapsedMinutes, float speedMph, float inclineDegrees) {
            this.timeElapsedMinutes = timeElapsedMinutes;
            this.speedMph = speedMph;
            this.inclineDegrees = inclineDegrees;
        }
    }


    private final BroadcastReceiver heartRateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double bpm = intent.getDoubleExtra("bpm", 0.0);
            long now = System.currentTimeMillis();

            if (lastCalorieUpdateMs > 0 && bpm > 90) {
                double elapsedMinutes = (now - lastCalorieUpdateMs) / 60000.0;
                double calPerMin;
                if (IS_MALE) {
                    calPerMin = (-55.0969 + (0.6309 * bpm) + (0.09036 * WEIGHT_KG) + (0.2017 * AGE)) / 4.184;
                } else {
                    calPerMin = (-20.4022 + (0.4472 * bpm) - (0.05741 * WEIGHT_KG) + (0.074 * AGE)) / 4.184;
                }
                if (calPerMin > 0) totalCalories += (calPerMin * elapsedMinutes);
            }
            lastCalorieUpdateMs = now;

            mainHandler.post(() -> {
                if (tvHeartRate != null) {
                    tvHeartRate.setText(String.format("❤️ HR\n%.0f\nbpm", bpm));
                }
                if (tvCalories != null) {
                    tvCalories.setText(String.format("🔥 Cals\n%.0f\nkcal", totalCalories));
                }
            });
        }
    };


    private android.widget.SeekBar seekBarAudio;
    private boolean isUserDraggingSeekBar = false;
    private boolean isMediaPlaying = false;

    // The ticker to move the music slider forward every second
    private final Runnable seekTicker = new Runnable() {
        @Override
        public void run() {
            if (isMediaPlaying && seekBarAudio != null && !isUserDraggingSeekBar) {
                seekBarAudio.setProgress(seekBarAudio.getProgress() + 1000);
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String trackText = intent.getStringExtra("track_text");
            if (tvTrackInfo != null && trackText != null) tvTrackInfo.setText(trackText);

            byte[] artBytes = intent.getByteArrayExtra("album_art");
            if (ivAlbumArt != null && artBytes != null) {
                ivAlbumArt.setImageBitmap(BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length));
            }


            long duration = intent.getLongExtra("duration_ms", 0);
            long position = intent.getLongExtra("position_ms", 0);
            isMediaPlaying = intent.getBooleanExtra("is_playing", false);

            if (seekBarAudio != null && duration > 0) {
                seekBarAudio.setMax((int) duration);
                if (!isUserDraggingSeekBar) {
                    seekBarAudio.setProgress((int) position);
                }
            }
        }
    };


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        if (getArguments() != null) deviceAddress = getArguments().getString("device");
        setupLocationTracking();
    }

    private void setupLocationTracking() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {


                    if (!location.hasAccuracy() || location.getAccuracy() > 15.0f) {
                        continue;
                    }

                    if (location.hasSpeed()) {
                        currentSpeedMph = location.getSpeed() * 2.23694f;


                        if (currentSpeedMph < 2.5f) {
                            currentSpeedMph = 0f;
                        }

                        // Track Max Speed
                        if (currentSpeedMph > maxSpeed) maxSpeed = currentSpeedMph;

                        // Update UI
                        if (tvHall != null) tvHall.setText(String.format("%.1f", currentSpeedMph));

                        // Accumulate Distance
                        long now = System.currentTimeMillis();
                        if (lastSpeedUpdateMs > 0 && currentSpeedMph > 0) {
                            float elapsedHours = (now - lastSpeedUpdateMs) / 3600000.0f;
                            totalDistanceMiles += currentSpeedMph * elapsedHours;
                        }
                        lastSpeedUpdateMs = now;

                        if (tvDistance != null) tvDistance.setText(String.format("Dist\n%.2f\nmi", totalDistanceMiles));



                        // store data for the summary
                        if (rideStartTimeMs == 0) rideStartTimeMs = now;
                        float elapsedMinutes = (now - rideStartTimeMs) / 60000.0f;
                        rideHistory.add(new RideDataPoint(elapsedMinutes, currentSpeedMph, currentIncline));
                    }
                }
            }
        };
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        tvAccel     = view.findViewById(R.id.tv_accel);
        tvHall      = view.findViewById(R.id.tv_hall);
        tvHeartRate = view.findViewById(R.id.tv_heart_rate);
        tvCalories  = view.findViewById(R.id.tv_calories);
        tvDistance  = view.findViewById(R.id.tv_distance);
        tvEspStatus = view.findViewById(R.id.tv_esp_status);
        tvTrackInfo = view.findViewById(R.id.tv_track_info);
        ivAlbumArt  = view.findViewById(R.id.iv_album_art);
        tvTrackInfo.setSelected(true);

        Button btnScanEsp = view.findViewById(R.id.btn_scan_esp);
        btnScanEsp.setOnClickListener(v -> startEspScan());

        Button btnStopRide = view.findViewById(R.id.btn_stop_ride);
        btnStopRide.setOnClickListener(v -> showRideSummary());

        Button btnPlayPause = view.findViewById(R.id.btn_play_pause);
        Button btnNext = view.findViewById(R.id.btn_next);
        Button btnPrev = view.findViewById(R.id.btn_prev);
        btnPlayPause.setOnClickListener(v -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        btnNext.setOnClickListener(v -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT));
        btnPrev.setOnClickListener(v -> sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS));

        seekBarAudio = view.findViewById(R.id.seek_bar_audio);
        seekBarAudio.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {}

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                isUserDraggingSeekBar = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                isUserDraggingSeekBar = false;

                Intent seekIntent = new Intent("MEDIA_SEEK_COMMAND");
                seekIntent.putExtra("seek_to_ms", (long) seekBar.getProgress());
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(seekIntent);
            }
        });

        // Start the UI clock for the slider
        mainHandler.post(seekTicker);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(heartRateReceiver, new IntentFilter("HEART_RATE_UPDATE"));
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mediaReceiver, new IntentFilter("MEDIA_TRACK_UPDATE"));

        if (!NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().getPackageName())) {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            Toast.makeText(getContext(), "Please grant Notification Access for media", Toast.LENGTH_LONG).show();
        }

        if (requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).setMinUpdateIntervalMillis(500).build();
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }

        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(heartRateReceiver);
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mediaReceiver);
        if (fusedLocationClient != null) fusedLocationClient.removeLocationUpdates(locationCallback);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False) disconnect();
        disconnectEsp();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null) service.attach(this);
        else getActivity().startService(new Intent(getActivity(), SerialService.class));
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations()) service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch (Exception ignored) {}
        super.onDetach();
    }

    private void sendMediaCommand(int keyCode) {
        AudioManager audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }


    private static final int REQUEST_BLE_PERMISSIONS = 2;
    private void startEspScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (requireActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    requireActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLE_PERMISSIONS);
                return;
            }
        } else {
            if (requireActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLE_PERMISSIONS);
                return;
            }
        }
        doStartScan();
    }
    @SuppressLint("MissingPermission")
    private void doStartScan() {
        foundDevices.clear();
        if (tvEspStatus != null) tvEspStatus.setText("Scanning for ESP32...");
        if (bleScanner == null) bleScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice() != null && !foundDevices.contains(result.getDevice())) foundDevices.add(result.getDevice());
            }
        };
        bleScanner.startScan(scanCallback);
        mainHandler.postDelayed(() -> {
            bleScanner.stopScan(scanCallback);
            showDevicePicker();
        }, 5000);
    }
    @SuppressLint("MissingPermission")
    private void showDevicePicker() {
        if (!isAdded()) return;
        if (foundDevices.isEmpty()) {
            if (tvEspStatus != null) tvEspStatus.setText("No devices found");
            return;
        }
        String[] names = new String[foundDevices.size()];
        for (int i = 0; i < foundDevices.size(); i++) {
            names[i] = (foundDevices.get(i).getName() != null ? foundDevices.get(i).getName() : "Unknown") + "\n" + foundDevices.get(i).getAddress();
        }
        new AlertDialog.Builder(requireActivity()).setTitle("Select ESP32").setItems(names, (dialog, which) -> connectEsp(foundDevices.get(which))).show();
    }


    @SuppressLint("MissingPermission")
    private void connectEsp(BluetoothDevice device) {
        if (tvEspStatus != null) tvEspStatus.setText("Connecting to " + device.getName() + "...");
        espGatt = device.connectGatt(requireContext(), false, gattCallback);
    }
    @SuppressLint("MissingPermission")
    private void disconnectEsp() {
        if (espGatt != null) { espGatt.close(); espGatt = null; }
    }
    @SuppressLint("MissingPermission")
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post(() -> { if (tvEspStatus != null) tvEspStatus.setText("ESP32 Connected ✓"); });
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mainHandler.post(() -> { if (tvEspStatus != null) tvEspStatus.setText("ESP32 Disconnected"); });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService espService = gatt.getService(UUID.fromString(ESP_SERVICE_UUID));
            if (espService == null) return;
            pendingNotifications.clear();
            BluetoothGattCharacteristic imuChar = espService.getCharacteristic(UUID.fromString(IMU_CHAR_UUID));
            if (imuChar != null) pendingNotifications.add(imuChar);
            enableNextNotification(gatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            enableNextNotification(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            String data = characteristic.getStringValue(0);
            if (characteristic.getUuid().toString().equalsIgnoreCase(IMU_CHAR_UUID)) {
                mainHandler.post(() -> {
                    try { currentIncline = Float.parseFloat(data); } catch (Exception ignored) {}
                    if (currentIncline > maxIncline) maxIncline = currentIncline;
                    if (tvAccel != null) tvAccel.setText(String.format("%.1f°", currentIncline));
                });
            }
        }
    };
    @SuppressLint("MissingPermission")
    private void enableNextNotification(BluetoothGatt gatt) {
        if (pendingNotifications.isEmpty()) return;
        BluetoothGattCharacteristic ch = pendingNotifications.remove(0);
        gatt.setCharacteristicNotification(ch, true);
        BluetoothGattDescriptor descriptor = ch.getDescriptor(UUID.fromString(CCCD_UUID));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }
    }


    private void showRideSummary() {
        if (rideHistory.isEmpty()) {
            Toast.makeText(getContext(), "No ride data collected yet!", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_ride_summary, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvStats = dialogView.findViewById(R.id.tv_summary_stats);
        Spinner spinner = dialogView.findViewById(R.id.spinner_graph_type);
        LineChart chart = dialogView.findViewById(R.id.chart_ride);
        Button btnClose = dialogView.findViewById(R.id.btn_close_summary);

        tvStats.setText(String.format("Distance: %.2f mi\nCalories: %.0f kcal\nMax Speed: %.1f mph\nMax Incline: %.1f°",
                totalDistanceMiles, totalCalories, maxSpeed, maxIncline));

        String[] graphOptions = {"Speed over Time", "Incline over Time"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, graphOptions);
        spinner.setAdapter(adapter);

        Runnable updateChart = () -> {
            List<Entry> entries = new ArrayList<>();
            boolean showSpeed = spinner.getSelectedItemPosition() == 0;
            for (RideDataPoint point : rideHistory) {
                float yValue = showSpeed ? point.speedMph : point.inclineDegrees;
                entries.add(new Entry(point.timeElapsedMinutes, yValue));
            }
            LineDataSet dataSet = new LineDataSet(entries, showSpeed ? "Speed (mph)" : "Incline (°)");
            dataSet.setColor(showSpeed ? Color.CYAN : Color.MAGENTA);
            dataSet.setDrawCircles(false);
            dataSet.setLineWidth(2f);
            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            chart.setData(new LineData(dataSet));
            chart.getDescription().setEnabled(false);
            chart.getXAxis().setTextColor(Color.WHITE);
            chart.getAxisLeft().setTextColor(Color.WHITE);
            chart.getAxisRight().setEnabled(false);
            chart.getLegend().setTextColor(Color.WHITE);
            chart.invalidate();
        };

        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { updateChart.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        btnClose.setOnClickListener(v -> {
            rideHistory.clear();
            totalDistanceMiles = 0f;
            totalCalories = 0.0;
            maxSpeed = 0f;
            maxIncline = 0f;
            rideStartTimeMs = 0;
            dialog.dismiss();
        });

        dialog.show();
        updateChart.run();
    }


    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    private void connect() {
        try {

        } catch (Exception e) { onSerialConnectError(e); }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if (connected != Connected.True) return;
        try {
            String msg = str;
            byte[] data = (str + newline).getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) { onSerialIoError(e); }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            String msg = new String(data);
            spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }
        receiveText.append(spn);
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    private void appendStatus(String str) { mainHandler.post(() -> status(str)); }

    // =========================================================================
    // Options menu
    // =========================================================================
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) { inflater.inflate(R.menu.menu_terminal, menu); }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) { receiveText.setText(""); return true; }
        else if (id == R.id.hex) { hexEnabled = !hexEnabled; return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) doStartScan();
            else Toast.makeText(getActivity(), "Bluetooth permissions required", Toast.LENGTH_LONG).show();
            return;
        }
    }


    @Override
    public void onSerialConnect() { status("connected"); connected = Connected.True; }
    @Override
    public void onSerialConnectError(Exception e) { status("connection failed: " + e.getMessage()); disconnect(); }
    @Override
    public void onSerialRead(byte[] data) { ArrayDeque<byte[]> datas = new ArrayDeque<>(); datas.add(data); receive(datas); }
    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) { receive(datas); }
    @Override
    public void onSerialIoError(Exception e) { status("connection lost: " + e.getMessage()); disconnect(); }
}