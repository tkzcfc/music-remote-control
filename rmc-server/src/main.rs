pub mod net;
mod peer;
mod player;
mod proto;

use crate::net::session_delegate::SessionDelegate;
use crate::net::tcp_server;
use crate::peer::Peer;
use crate::player::Player;
use clap::Parser;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::signal;
use tokio::sync::Mutex;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct Opts {
    /// The address to listen on
    #[arg(long, default_value = "0.0.0.0:8000")]
    pub listen_addr: String,

    /// Authorization code
    #[arg(long, default_value = "abc123")]
    pub authorization_code: String,
}

pub static GLOBAL_OPTS: Lazy<Opts> = Lazy::new(|| Opts::parse());

pub struct GlobalContext {
    players: Mutex<HashMap<u32, Arc<Player>>>,
}

pub static GLOBAL_CONTEXT: Lazy<GlobalContext> = Lazy::new(|| GlobalContext {
    players: Mutex::new(HashMap::new()),
});

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    Lazy::force(&GLOBAL_OPTS);

    tcp_server::Builder::new(Box::new(|| -> Box<dyn SessionDelegate> {
        Box::new(Peer::new())
    }))
    .build(GLOBAL_OPTS.listen_addr.as_str(), signal::ctrl_c())
    .await?;

    Ok(())
}
