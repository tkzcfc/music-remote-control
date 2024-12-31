package com.mrc.client;

import androidx.annotation.Nullable;
import androidx.core.view.OnApplyWindowInsetsListener;

public class TcpClient {
    // 用于保存C++对象指针
    private long nativeHandle;

    private TcpClientListener onMessageReceivedListener;

    public native void start(int max_channel_count);

    public native void stop();

    // 小于0表示出错，其他返回值表示新的连接id
    public native int connect(String host, int port, int kind);

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
