#include "yasio_client.h"
#include "yasio/yasio.hpp"

#define NET_LOG_ENABLED 0

#if NET_LOG_ENABLED
#define LOG_TAG "yasio_client"
#define NET_LOG(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define NET_LOG(...) \
        do             \
        {              \
        } while (0)
#endif

yasio_client_service_opt_callback_type yasio_client::on_yasio_service_opt_callback = nullptr;

yasio_client::yasio_client()
        : m_connectionIdSeed(0)
        , m_eventCallback(nullptr)
{

}

yasio_client::~yasio_client()
{
    delete m_service;
}

void yasio_client::start(int maxChannelCount)
{
    if (maxChannelCount <= 0)
        maxChannelCount = 1;

    m_connectQue.reserve(maxChannelCount);

    m_service = new yasio::io_service(maxChannelCount);
    m_service->set_option(yasio::YOPT_S_CONNECT_TIMEOUT, 5);
    m_service->set_option(yasio::YOPT_S_DNS_QUERIES_TIMEOUT, 3);
    m_service->set_option(yasio::YOPT_S_DNS_QUERIES_TRIES, 1);
    m_service->set_option(yasio::YOPT_S_FORWARD_PACKET, 1);
    m_service->set_option(yasio::YOPT_S_NO_NEW_THREAD, 1);
    //m_service->set_option(yasio::YOPT_S_TCP_KEEPALIVE, 5, 5, 3);

    for (auto i = 0; i < maxChannelCount; ++i)
    {
        m_availChannelQueue.push(i);
        m_service->set_option(yasio::YOPT_C_UNPACK_PARAMS, i, 1024 * 1024 * 10, 0, 4, 4);
        m_service->set_option(yasio::YOPT_C_UNPACK_STRIP, i, 4);
        m_service->set_option(yasio::YOPT_C_UNPACK_NO_BSWAP, i, 0);

        if (on_yasio_service_opt_callback)
            on_yasio_service_opt_callback(m_service, i);
    }
    if (on_yasio_service_opt_callback)
        on_yasio_service_opt_callback(m_service, -1);

    on_yasio_service_opt_callback = nullptr;

    m_service->start([this](yasio::event_ptr&& e) { handleNetworkEvent(e.get()); });
}

void yasio_client::stop()
{
    on_yasio_service_opt_callback = nullptr;
    if(m_service)
    {
        m_service->stop();
    }
}

int yasio_client::nextConnectionId()
{
    return m_connectionIdSeed + 1;
}

void yasio_client::connect(const std::string& host, int port, int kind)
{
    auto id = m_connectionIdSeed + 1;
    m_connectionIdSeed = id;
    Connection conn;
    conn.id = id;
    conn.status = ConnectionStatus::Queuing;
    conn.port = port;
    conn.host = host;
    conn.kind = kind;
    conn.channel = -1;
    conn.transport = nullptr;

    m_queLock.lock();
    m_connectQue.push_back(conn);
    m_queLock.unlock();

    doConnect();
}

void yasio_client::disconnect(int connectionId)
{
    std::lock_guard<std::mutex> _lockguard1(m_connectListLock);
    auto it = m_aliveConnects.find(connectionId);
    if (it != m_aliveConnects.end())
    {
        m_service->close(it->second.channel);
        return;
    }

    std::lock_guard<std::mutex> _lockguard2(m_queLock);
    for (auto it = m_connectQue.begin(); it != m_connectQue.end(); ++it)
    {
        if (it->id == connectionId)
        {
            m_connectQue.erase(it);
            break;
        }
    }
}

int yasio_client::send(int connectionId, const char* data, size_t length)
{
    std::lock_guard<std::mutex> _lockguard(m_connectListLock);

    auto it = m_aliveConnects.find(connectionId);
    if (it == m_aliveConnects.end()) {
        return -1000;
    }

    auto& transport = it->second.transport;
    if (!transport)
        return -1001;

    return m_service->write(transport, data, length);
}

void yasio_client::setEventCallback(const yasio_client_event_callback_type& callback)
{
    m_eventCallback = callback;
}

void yasio_client::handleNetworkEvent(yasio::io_event* event)
{
    int channelIndex = event->cindex();
    auto channel = m_service->channel_at(channelIndex);
    int connectionId = channel->ud_.ival;

    switch (event->kind())
    {
        case yasio::YEK_ON_OPEN:
        {
            if (event->status() == 0)
            {
                {
                    std::lock_guard<std::mutex> _lockguard(m_connectListLock);
                    auto it = m_aliveConnects.find(connectionId);
                    if (it != m_aliveConnects.end())
                    {
                        it->second.transport = event->transport();
                    }
                }
                NET_LOG("net: connect success, id = %d", connectionId);
                dispatchEvent(event_type::OnConnectSuccess, connectionId, 0, 0);
            }
            else
            {
                NET_LOG("net: connect failed, id = %d", connectionId);
                handleNetworkEOF(channel, event->status());

                char err[64];
                sprintf(err, "connect failed, internal error code: %d", event->status());
                dispatchEvent(event_type::OnConnectFailed, connectionId, err, strlen(err) + 1);
            }
        }
            break;
        case yasio::YEK_ON_CLOSE:
        {
            NET_LOG("net: disconnect, id = %d", connectionId);
            handleNetworkEOF(channel, event->status());

            char err[64];
            sprintf(err, "disconnect, internal error code: %d", event->status());
            dispatchEvent(event_type::OnDisconnect, connectionId, err, strlen(err) + 1);
        }
            break;
        case yasio::YEK_ON_PACKET:
        {
            auto& packet = event->packet();
            dispatchEvent(event_type::OnRecvData, connectionId, packet.data(), packet.size());
        }
            break;
    }

    doConnect();
}

void yasio_client::handleNetworkEOF(yasio::io_channel* channel, int internalErrorCode)
{
    std::lock_guard<std::mutex> _lockguard1(m_connectListLock);
    std::lock_guard<std::mutex> _lockguard2(m_queLock);

    int connectionId = channel->ud_.ival;
    channel->ud_.ival = -1;

    auto it = m_aliveConnects.find(connectionId);
    if (it != m_aliveConnects.end())
    {
        m_aliveConnects.erase(it);
    }
    // 回收信道
    m_availChannelQueue.push(channel->index());
}

void yasio_client::doConnect()
{
    while (true) {
        m_queLock.lock();
        if(m_connectQue.empty()) {
            m_queLock.unlock();
            break;
        }

        auto& conn = m_connectQue.front();
        auto channel = tryTakeAvailChannel();
        if (channel < 0) {
            m_queLock.unlock();
            break;
        }

        conn.channel = channel;
        m_connectQue.erase(m_connectQue.begin());
        m_queLock.unlock();

        {
            m_connectListLock.lock();
            m_aliveConnects.insert(std::make_pair(conn.id, conn));
            m_connectListLock.unlock();
        }


        auto channelHandle = m_service->channel_at(channel);
        NET_LOG("net: open connection for %s:%d, id = %d", conn.host.data(), conn.port, conn.id);
        channelHandle->ud_.ival = conn.id;

        m_service->set_option(yasio::YOPT_C_REMOTE_ENDPOINT, channel, conn.host.data(), conn.port);
        m_service->open(channel, conn.kind);
    }
}

int yasio_client::tryTakeAvailChannel()
{
    if (!m_availChannelQueue.empty())
    {
        int channel = m_availChannelQueue.front();
        m_availChannelQueue.pop();
        return channel;
    }
    return -1;
}

