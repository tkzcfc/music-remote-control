#pragma once

#include "yasio/yasio.hpp"
#include "yasio/obstream.hpp"
#include <unordered_map>

typedef std::function<void(int, int, const std::string_view&)> yasio_client_event_callback_type;
typedef std::function<void(yasio::io_service*, int)> yasio_client_service_opt_callback_type;

class yasio_client final
{
public:

    enum event_type: int
    {
        OnConnectSuccess,
        OnConnectFailed,
        OnDisconnect,
        OnRecvData
    };

    static yasio_client_service_opt_callback_type on_yasio_service_opt_callback;

public:

    yasio_client();

    ~yasio_client();

    void start(int max_channel_count);

    void stop();

    // 小于0表示出错，其他返回值表示新的连接id
    int connect(const std::string& host, int port, int kind);

    void disconnect(int connectionId);

    int send(int connectionId, const char* data, size_t length);

    void setEventCallback(const yasio_client_event_callback_type& callback);

private:

    void handleNetworkEvent(yasio::io_event* event);

    void handleNetworkEOF(yasio::io_channel* channel, int internalErrorCode);

    void doConnect();

    int tryTakeAvailChannel();

    inline void dispatchEvent(event_type evtType, int connectionId, char* data, size_t length)
    {
        if (m_eventCallback)
        {
            m_eventCallback(evtType, connectionId, std::string_view(data, length));
        }
    }

private:
    yasio::io_service* m_service;

    enum ConnectionStatus : uint8_t
    {
        Queuing,
        Connecting,
        Connected,
        Disconnecting,
        Disconnected
    };

    struct Connection
    {
        std::string host;
        int port;
        int id;
        int kind;
        int channel;
        yasio::transport_handle_t transport;
        ConnectionStatus status;
    };
    int m_connectionIdSeed;
    // 等待连接队列
    std::vector<Connection> m_connectQue;
    // 已连接列表
    std::unordered_map<int, Connection> m_aliveConnects;
    // 有效信道列表
    std::queue<int> m_availChannelQueue;

    yasio_client_event_callback_type m_eventCallback;
};
