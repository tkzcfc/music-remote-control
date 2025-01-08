use crate::proto;
use anyhow::anyhow;
use byteorder::{BigEndian, ByteOrder};
use bytes::BytesMut;
use serde::Serialize;
use socket2::{SockRef, TcpKeepalive};
use std::fmt::{Display, Formatter};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::net::{TcpStream, ToSocketAddrs};
use tokio::select;
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio::sync::{mpsc, Mutex, RwLock};
use tokio::time::{sleep, Instant};

#[derive(Clone)]
pub enum ControlServiceStatus {
    Connected,
    Connecting,
    Disconnected,
}

impl Display for ControlServiceStatus {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            ControlServiceStatus::Connected => write!(f, "Connected"),
            ControlServiceStatus::Connecting => write!(f, "Connecting"),
            ControlServiceStatus::Disconnected => write!(f, "Disconnected"),
        }
    }
}

pub struct ControlService {
    pub status: Arc<RwLock<ControlServiceStatus>>,
    pub tx: RwLock<Option<UnboundedSender<proto::Message>>>,
}

impl ControlService {
    pub fn new() -> Self {
        Self {
            status: Arc::new(RwLock::new(ControlServiceStatus::Disconnected)),
            tx: RwLock::new(None),
        }
    }
    pub async fn get_status(&self) -> String {
        self.status.read().await.to_string()
    }

    pub async fn run(
        &self,
        mut read_js_rx: mpsc::Receiver<String>,
        write_to_js_tx: mpsc::Sender<String>,
    ) -> anyhow::Result<(), Box<dyn std::error::Error + Send + Sync>> {
        while let Some(input) = read_js_rx.recv().await {
            let message: proto::Message = serde_json::from_str(&input)?;
            match message.name.as_str() {
                "ConnectRequest" => self.on_connect_request(&message, &write_to_js_tx).await?,
                _ => {
                    if let Some(ref tx) = *self.tx.read().await {
                        let _ = tx.send(message);
                    }
                }
            }
        }
        Ok(())
    }

    async fn on_connect_request(
        &self,
        message: &proto::Message,
        write_to_js_tx: &mpsc::Sender<String>,
    ) -> anyhow::Result<()> {
        let current_status = {
            let read_guard = self.status.read().await;
            read_guard.clone()
        };
        match current_status {
            ControlServiceStatus::Connected => {
                send_message_to_js(
                    &write_to_js_tx,
                    &proto::ConnectResponse {
                        ok: true,
                        error: "".to_string(),
                    },
                )
                .await?;
            }
            ControlServiceStatus::Connecting => {}
            ControlServiceStatus::Disconnected => {
                let request: proto::ConnectRequest = serde_json::from_str(&message.data)?;

                *self.status.write().await = ControlServiceStatus::Connecting;
                match do_connect(&request.addr).await {
                    Ok(stream) => {
                        let (tx, rx) = unbounded_channel();

                        *self.tx.write().await = Some(tx);
                        *self.status.write().await = ControlServiceStatus::Connected;
                        send_message_to_js(
                            &write_to_js_tx,
                            &proto::ConnectResponse {
                                ok: true,
                                error: "".to_string(),
                            },
                        )
                        .await?;

                        let status = self.status.clone();
                        let write_to_js_tx = write_to_js_tx.clone();
                        tokio::spawn(async move {
                            let (reader, writer) = tokio::io::split(stream);

                            let writer = Arc::new(Mutex::new(writer));
                            let last_active_time = Arc::new(RwLock::new(Instant::now()));

                            let result;
                            select! {
                                r1= run_client(rx, reader, writer.clone(), last_active_time.clone(), write_to_js_tx.clone()) => { result = r1 },
                                r2= ping_forever(writer, last_active_time.clone()) => { result = r2 },
                            }

                            *status.write().await = ControlServiceStatus::Disconnected;

                            let reason;
                            if let Err(err) = result {
                                reason = err.to_string();
                                println!("client error: {}", reason);
                            } else {
                                reason = String::from("");
                            }
                            let _ = send_message_to_js(
                                &write_to_js_tx,
                                &proto::DisconnectNtf { reason },
                            )
                            .await;
                        });
                    }
                    Err(err) => {
                        *self.status.write().await = ControlServiceStatus::Disconnected;
                        send_message_to_js(
                            &write_to_js_tx,
                            &proto::ConnectResponse {
                                ok: false,
                                error: err.to_string(),
                            },
                        )
                        .await?;
                    }
                }
            }
        }
        Ok(())
    }
}

async fn run_client<S>(
    rx: UnboundedReceiver<proto::Message>,
    reader: ReadHalf<S>,
    writer: Arc<Mutex<WriteHalf<S>>>,
    last_active_time: Arc<RwLock<Instant>>,
    write_to_js_tx: mpsc::Sender<String>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    let result;
    select! {
        r1 = poll_read(reader, writer.clone(), last_active_time, write_to_js_tx) => { result = r1 }
        r2 = poll_write(rx, writer) => { result = r2 }
    }
    result
}

async fn poll_read<S>(
    mut reader: ReadHalf<S>,
    writer: Arc<Mutex<WriteHalf<S>>>,
    last_active_time: Arc<RwLock<Instant>>,
    write_to_js_tx: mpsc::Sender<String>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    const WRITE_TIMEOUT: Duration = Duration::from_secs(1);
    let mut buffer = BytesMut::with_capacity(65536);
    loop {
        let len = reader.read_buf(&mut buffer).await?;
        // len为0表示对端已经关闭连接。
        if len == 0 {
            return Err(anyhow!("disconnect from the server"));
        } else {
            if last_active_time.read().await.elapsed() >= WRITE_TIMEOUT {
                let mut instant_write = last_active_time.write().await;
                *instant_write = Instant::now();
            }

            // 循环解包
            loop {
                if buffer.is_empty() {
                    break;
                }

                let result = try_extract_frame(&mut buffer)?;
                if let Some(frame) = result {
                    let str = String::from_utf8(frame)?;
                    let message: proto::Message = serde_json::from_str(&str)?;

                    // 收到完整消息
                    on_recv_message(&writer, message, &write_to_js_tx).await?;
                } else {
                    // 消息包接收还未完成
                    break;
                }
            }
        }
    }
}

async fn on_recv_message<S>(
    writer: &Arc<Mutex<WriteHalf<S>>>,
    message: proto::Message,
    write_to_js_tx: &mpsc::Sender<String>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    match message.name.as_str() {
        "Ping" => {
            let ping: proto::Ping = serde_json::from_str(&message.data)?;
            send_message_to_server(writer, &proto::Pong { time: ping.time }).await?;
        }
        _ => {
            let str = serde_json::to_string(&message)?;
            write_to_js_tx.send(str).await?;
        }
    }

    Ok(())
}

/// 数据粘包处理
///
/// 注意：这个函数只能使用消耗 buffer 数据的函数，否则框架会一直循环调用本函数来驱动处理消息
///
fn try_extract_frame(buffer: &mut BytesMut) -> anyhow::Result<Option<Vec<u8>>> {
    // 数据小于4字节,继续读取数据
    if buffer.len() < 4 {
        return Ok(None);
    }

    // 读取包长度
    let buf = buffer.get(0..4).unwrap();
    let len = BigEndian::read_u32(buf) as usize;

    // 超出最大限制
    if len <= 0 || len >= 1024 * 1024 * 2 {
        return Err(anyhow!("Message too long"));
    }

    // 数据不够,继续读取数据
    if buffer.len() < 4 + len {
        return Ok(None);
    }

    // 拆出这个包的数据
    let frame = buffer.split_to(4 + len).split_off(4).to_vec();

    Ok(Some(frame))
}

async fn poll_write<S>(
    mut rx: UnboundedReceiver<proto::Message>,
    writer: Arc<Mutex<WriteHalf<S>>>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    while let Some(message) = rx.recv().await {
        let str = serde_json::to_string(&message)?;

        let mut buf = Vec::with_capacity(str.len() + 4);
        byteorder::WriteBytesExt::write_u32::<BigEndian>(&mut buf, str.len() as u32)?;
        buf.extend_from_slice(str.as_bytes());

        writer.lock().await.write_all(&buf).await?;
    }
    Ok(())
}

async fn ping_forever<S>(
    writer: Arc<Mutex<WriteHalf<S>>>,
    last_active_time: Arc<RwLock<Instant>>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Send + 'static,
{
    const PING_INTERVAL: Duration = Duration::from_secs(5);
    const PING_TIMEOUT: Duration = Duration::from_secs(5);
    loop {
        sleep(Duration::from_secs(1)).await;

        if last_active_time.read().await.elapsed() < PING_INTERVAL {
            continue;
        }

        if last_active_time.read().await.elapsed() > PING_TIMEOUT {
            return Err(anyhow!("ping timeout"));
        }

        // 获取当前时间
        let now = SystemTime::now();

        // 计算自UNIX_EPOCH以来的持续时间
        let since_epoch = now.duration_since(UNIX_EPOCH).expect("Time went backwards");

        // 将时间转换为毫秒
        let nanos = since_epoch.as_millis();

        send_message_to_server(&writer, &proto::Ping { time: nanos as u64 }).await?;
    }
}

async fn do_connect<A: ToSocketAddrs>(addr: &A) -> anyhow::Result<TcpStream> {
    let stream = TcpStream::connect(&addr).await?;

    let ka = TcpKeepalive::new().with_time(Duration::from_secs(30));
    let sf = SockRef::from(&stream);
    sf.set_tcp_keepalive(&ka)?;
    Ok(stream)
}

fn type_name_of<T>() -> &'static str {
    let full_type_name = std::any::type_name::<T>();
    full_type_name
        .rsplit("::")
        .next()
        .unwrap_or_else(|| full_type_name)
}

async fn send_message_to_server<T, U>(
    writer: &Arc<Mutex<WriteHalf<T>>>,
    data: &U,
) -> anyhow::Result<()>
where
    T: AsyncRead + AsyncWrite + Send + 'static,
    U: Serialize,
{
    let message = proto::Message {
        name: type_name_of::<U>().to_string(),
        data: serde_json::to_string(data)?,
    };

    let str = serde_json::to_string(&message)?;

    let mut buf = Vec::with_capacity(str.len() + 4);
    byteorder::WriteBytesExt::write_u32::<BigEndian>(&mut buf, str.len() as u32)?;
    buf.extend_from_slice(str.as_bytes());

    writer.lock().await.write_all(&buf).await?;
    Ok(())
}

async fn send_message_to_js<U>(tx: &mpsc::Sender<String>, data: &U) -> anyhow::Result<()>
where
    U: Serialize,
{
    let message = proto::Message {
        name: type_name_of::<U>().to_string(),
        data: serde_json::to_string(data)?,
    };

    let str = serde_json::to_string(&message)?;
    tx.send(str).await?;
    Ok(())
}
