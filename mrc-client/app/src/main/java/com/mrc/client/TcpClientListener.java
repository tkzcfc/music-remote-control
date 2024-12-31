package com.mrc.client;

public interface TcpClientListener {
    void onMessage(int event_type, int connection_id, byte[] data);
}
