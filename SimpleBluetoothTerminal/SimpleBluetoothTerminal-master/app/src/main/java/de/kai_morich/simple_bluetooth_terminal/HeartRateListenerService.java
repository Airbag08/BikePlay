package de.kai_morich.simple_bluetooth_terminal;

import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class HeartRateListenerService extends WearableListenerService {

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (messageEvent.getPath().equals("/heart_rate")) {
            double bpm = Double.parseDouble(new String(messageEvent.getData()));
            Log.d("HR_LISTENER", "Received HR from watch: " + bpm);

            Intent intent = new Intent("HEART_RATE_UPDATE");
            intent.putExtra("bpm", bpm);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }
}