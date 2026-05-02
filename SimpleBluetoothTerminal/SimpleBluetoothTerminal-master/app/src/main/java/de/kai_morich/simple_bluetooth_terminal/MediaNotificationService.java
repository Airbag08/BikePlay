package de.kai_morich.simple_bluetooth_terminal;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class MediaNotificationService extends NotificationListenerService {

    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            broadcastMediaUpdate(metadata, activeController != null ? activeController.getPlaybackState() : null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            broadcastMediaUpdate(activeController != null ? activeController.getMetadata() : null, state);
        }
    };


    private final BroadcastReceiver seekCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long seekPosition = intent.getLongExtra("seek_to_ms", 0);
            if (activeController != null && activeController.getTransportControls() != null) {
                activeController.getTransportControls().seekTo(seekPosition);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        LocalBroadcastManager.getInstance(this).registerReceiver(seekCommandReceiver, new IntentFilter("MEDIA_SEEK_COMMAND"));
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(seekCommandReceiver);
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName componentName = new ComponentName(this, MediaNotificationService.class);

        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(controllers -> {
                updateActiveController(controllers);
            }, componentName);
            updateActiveController(mediaSessionManager.getActiveSessions(componentName));
        } catch (SecurityException e) {
            Log.e("MediaService", "Missing notification access permission");
        }
    }

    private void updateActiveController(List<MediaController> controllers) {
        if (activeController != null) {
            activeController.unregisterCallback(mediaCallback);
        }
        if (controllers != null && !controllers.isEmpty()) {
            activeController = controllers.get(0);
            activeController.registerCallback(mediaCallback);
            broadcastMediaUpdate(activeController.getMetadata(), activeController.getPlaybackState());
        }
    }

    private void broadcastMediaUpdate(MediaMetadata metadata, PlaybackState state) {
        if (metadata == null) return;

        Intent intent = new Intent("MEDIA_TRACK_UPDATE");

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        intent.putExtra("track_text", (title != null ? title : "Unknown") + " • " + (artist != null ? artist : "Unknown"));


        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        intent.putExtra("duration_ms", duration);


        if (state != null) {
            intent.putExtra("position_ms", state.getPosition());
            intent.putExtra("is_playing", state.getState() == PlaybackState.STATE_PLAYING);
        }

        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (albumArt != null) {
            Bitmap scaledArt = Bitmap.createScaledBitmap(albumArt, 200, 200, true);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            scaledArt.compress(Bitmap.CompressFormat.PNG, 100, stream);
            intent.putExtra("album_art", stream.toByteArray());
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}