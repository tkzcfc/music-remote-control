package com.mrc.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ControlService extends Service {
    public static String notificationId = "test_channel_01";

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel channel = new NotificationChannel(notificationId, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.channel_description));
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

        startForeground(1, createNotification("Service started"));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // build a persistent notification and return it.
    public Notification createNotification(String message) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(getApplicationContext(), notificationId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)  //persistent notification!
                .setChannelId(notificationId)
                .setContentTitle("rmc-client")   //Title message top row.
                .setContentText(message)  //message when looking at the notification, second row
                .setContentIntent(pendingIntent)
                .build();  //finally build and return a Notification.
    }
}
