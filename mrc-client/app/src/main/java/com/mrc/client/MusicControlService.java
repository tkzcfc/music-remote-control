package com.mrc.client;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class MusicControlService extends NotificationListenerService {

    private MediaSessionManager mMediaSessionManager;
    private MediaController mMediaController;

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        initMediaController();
    }

    private void initMediaController() {
        for (MediaController controller : mMediaSessionManager.getActiveSessions(new ComponentName(this, NotificationListenerService.class))) {
            if ("com.android.music".equals(controller.getPackageName())) {
                mMediaController = controller;
                break;
            }
        }
    }

    public void playNext() {
        if (mMediaController != null) {
            mMediaController.getTransportControls().skipToNext();
        }
    }

    public void playPrevious() {
        if (mMediaController != null) {
            mMediaController.getTransportControls().skipToPrevious();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        initMediaController();
    }
}
