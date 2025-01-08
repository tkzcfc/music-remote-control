use crate::net::WriterMessage;
use crate::proto::{
    Message, Ping, PushMediaKeyEvent, SendControlMediaKeyEventRequest,
    SendControlMediaKeyEventResponse,
};
use crate::{GLOBAL_CONTEXT, GLOBAL_OPTS};
use byteorder::BigEndian;
use serde::Serialize;
use std::time::Duration;
use tokio::sync::mpsc::UnboundedSender;
use tokio::task::JoinHandle;
use tokio::time::sleep;

pub struct Player {
    pub tx: UnboundedSender<WriterMessage>,
    ping_task: JoinHandle<()>,
    session_id: u32,
}

impl Player {
    pub fn new(session_id: u32, tx: UnboundedSender<WriterMessage>) -> Self {
        let tx_cloned = tx.clone();
        Self {
            tx,
            ping_task: tokio::spawn(async move {
                loop {
                    let _ = send_message(&tx_cloned, &Ping { time: now_millis() });
                    sleep(Duration::from_secs(5)).await;
                }
            }),
            session_id,
        }
    }

    pub async fn on_recv_message(&self, message: &Message) -> anyhow::Result<()> {
        match message.name.as_str() {
            "Pong" => {}
            "SendControlMediaKeyEventRequest" => {
                let request: SendControlMediaKeyEventRequest = serde_json::from_str(&message.data)?;

                if GLOBAL_OPTS.authorization_code == request.authorization_code {
                    let push = PushMediaKeyEvent {
                        action: request.action,
                        code: request.code,
                        token: request.token,
                    };

                    for (_, (_, player)) in GLOBAL_CONTEXT
                        .players
                        .lock()
                        .await
                        .iter()
                        .filter(|(session_id, _)| **session_id != self.session_id)
                        .enumerate()
                    {
                        send_message(&player.tx, &push)?;
                    }

                    send_message(
                        &self.tx,
                        &SendControlMediaKeyEventResponse {
                            ok: true,
                            error: "".to_string(),
                        },
                    )?;
                } else {
                    send_message(
                        &self.tx,
                        &SendControlMediaKeyEventResponse {
                            ok: false,
                            error: "no permission".to_string(),
                        },
                    )?;
                }
            }
            _ => {}
        }

        Ok(())
    }

    pub async fn on_disconnect_session(&self) -> anyhow::Result<()> {
        self.ping_task.abort();
        Ok(())
    }
}

fn type_name_of<T>() -> &'static str {
    let full_type_name = std::any::type_name::<T>();
    full_type_name
        .rsplit("::")
        .next()
        .unwrap_or_else(|| full_type_name)
}

fn now_millis() -> u64 {
    let start = std::time::SystemTime::now();
    let since_the_epoch = start
        .duration_since(std::time::UNIX_EPOCH)
        .expect("Time went backwards");
    since_the_epoch.as_millis() as u64
}

fn send_message<U>(tx: &UnboundedSender<WriterMessage>, data: &U) -> anyhow::Result<()>
where
    U: Serialize,
{
    let message = Message {
        name: type_name_of::<U>().to_string(),
        data: serde_json::to_string(data)?,
    };

    let str = serde_json::to_string(&message)?;
    println!("send: {}", str);

    let mut buf = Vec::with_capacity(str.len() + 4);
    byteorder::WriteBytesExt::write_u32::<BigEndian>(&mut buf, str.len() as u32)?;
    buf.extend_from_slice(str.as_bytes());

    tx.send(WriterMessage::Send(buf, true))?;

    Ok(())
}
