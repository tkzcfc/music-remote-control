#[derive(serde::Serialize, serde::Deserialize)]
pub struct Message {
    pub name: String,
    pub data: String,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct Ping {
    pub time: u64,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct Pong {
    pub time: u64,
    pub authorization_code: Option<String>,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct PushMediaKeyEvent {
    pub action: u32,
    pub code: u32,
    pub token: String,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct SendControlMediaKeyEventRequest {
    pub action: u32,
    pub code: u32,
    pub token: String,
}

#[derive(serde::Serialize, serde::Deserialize)]
pub struct SendControlMediaKeyEventResponse {
    pub ok: bool,
    pub error: String,
}
