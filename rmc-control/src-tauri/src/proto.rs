#[derive(serde::Serialize, serde::Deserialize)]
pub struct Message {
    pub name: String,
    pub data: String,
}

//////////////////////////////////////////////// local ////////////////////////////////////////////////

#[derive(serde::Serialize, serde::Deserialize)]
pub struct ConnectRequest {
    pub addr: String,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct ConnectResponse {
    pub ok: bool,
    pub error: String,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct DisconnectNtf {
    pub reason: String,
}

//////////////////////////////////////////////// server ////////////////////////////////////////////////

#[derive(serde::Serialize, serde::Deserialize)]
pub struct Ping {
    pub time: u64,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct Pong {
    pub time: u64,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct SendControlMediaKeyEventRequest {
    pub action: u32,
    pub code: u32,
    pub token: String,
    pub authorization_code: String,
}
