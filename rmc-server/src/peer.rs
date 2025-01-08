use crate::net::session_delegate::SessionDelegate;
use crate::net::WriterMessage;
use crate::player::Player;
use crate::proto::Message;
use crate::GLOBAL_CONTEXT;
use anyhow::anyhow;
use async_trait::async_trait;
use byteorder::{BigEndian, ByteOrder};
use bytes::BytesMut;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::mpsc::UnboundedSender;

pub struct Peer {
    player: Option<Arc<Player>>,
    session_id: u32,
}

#[async_trait]
impl SessionDelegate for Peer {
    async fn on_session_start(
        &mut self,
        session_id: u32,
        _addr: &SocketAddr,
        tx: UnboundedSender<WriterMessage>,
    ) -> anyhow::Result<()> {
        let player = Arc::new(Player::new(session_id, tx));
        GLOBAL_CONTEXT
            .players
            .lock()
            .await
            .insert(session_id, player.clone());
        self.player = Some(player);
        self.session_id = session_id;
        Ok(())
    }

    // 会话关闭回调
    async fn on_session_close(&mut self) -> anyhow::Result<()> {
        self.player.take().unwrap().on_disconnect_session().await?;
        GLOBAL_CONTEXT.players.lock().await.remove(&self.session_id);
        Ok(())
    }

    /// 数据粘包处理
    ///
    /// 注意：这个函数只能使用消耗 buffer 数据的函数，否则框架会一直循环调用本函数来驱动处理消息
    ///
    async fn on_try_extract_frame(
        &mut self,
        buffer: &mut BytesMut,
    ) -> anyhow::Result<Option<Vec<u8>>> {
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

    // 收到一个完整的消息包
    async fn on_recv_frame(&mut self, bytes: Vec<u8>) -> anyhow::Result<()> {
        let str = String::from_utf8(bytes)?;
        println!("recv: {}", str);
        let message: Message = serde_json::from_str(&str)?;

        let result = self.player
            .as_ref()
            .unwrap()
            .on_recv_message(&message)
            .await;
        
        if let Err(ref err) = result {
            println!("on_recv_message err: {}", err.to_string());
        }

        result
    }
}

impl Peer {
    pub fn new() -> Self {
        Peer {
            player: None,
            session_id: 0,
        }
    }
}
