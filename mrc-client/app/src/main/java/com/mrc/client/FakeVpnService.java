package com.mrc.client;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;

public class FakeVpnService extends VpnService  {
    private ParcelFileDescriptor vpnInterface;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundService();
        setupVpn();
        return START_STICKY;
    }

    @SuppressLint("ForegroundServiceType")
    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, "vpn_channel")
                .setContentTitle("Fake VPN Service")
                .setContentText("Fake VPN is running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        startForeground(1, notification);
    }

    // https://github.com/asdzheng/vpnservices
    private void setupVpn() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            // Configure VPN settings
            Builder builder = new Builder();
            builder.setSession("Fake VPN")
                    .addAddress("10.0.0.2", 24) // Dummy IP address
                    .addRoute("0.0.0.0", 0) // Route all traffic through VPN
                    .addAllowedApplication("com.nobody.package");

            // Establish the VPN interface
            vpnInterface = builder.establish();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
