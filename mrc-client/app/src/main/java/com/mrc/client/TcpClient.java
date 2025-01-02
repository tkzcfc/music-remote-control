package com.mrc.client;

import androidx.annotation.Nullable;

public class TcpClient {
    public static final int EVENT_ON_CONNECT_SUCCESS = 0;
    public static final int EVENT_ON_CONNECT_FAILED = 1;
    public static final int EVENT_ON_DISCONNECT = 2;
    public static final int EVENT_ON_RECV_DATA = 3;


    // 用于保存C++对象指针
    private long nativeHandle;

    private TcpClientListener onMessageReceivedListener;

    public native void start(int max_channel_count);

    public native void stop();

    public native int nextConnectionId();

    public native void connect(String host, int port, int kind);

    public native void disconnect(int connection_id);

    public native int send(int connection_id, byte[] data);

    public void onMessageReceived(int event_type, int connection_id, byte[] data) {
        if (onMessageReceivedListener != null) {
            onMessageReceivedListener.onMessage(event_type, connection_id, data);
        }
    }

    public void setMessageReceivedListener(final @Nullable TcpClientListener listener) {
        onMessageReceivedListener = listener;
    }
}
