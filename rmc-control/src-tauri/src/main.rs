// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use crate::control_service::ControlService;
use log::info;
use std::sync::Arc;
use tauri::Manager;
use tokio::sync::mpsc;
use tokio::sync::Mutex;

mod control_service;
mod proto;

struct AsyncProcInputTx {
    inner: Mutex<mpsc::Sender<String>>,
    control_service: Arc<ControlService>,
}

fn main() {
    let (async_proc_input_tx, async_proc_input_rx) = mpsc::channel(1);
    let (async_proc_output_tx, mut async_proc_output_rx) = mpsc::channel(1);

    let control_service = Arc::new(ControlService::new());

    tauri::Builder::default()
        .manage(AsyncProcInputTx {
            inner: Mutex::new(async_proc_input_tx),
            control_service: control_service.clone(),
        })
        .invoke_handler(tauri::generate_handler![js2rs, get_control_service_status])
        .setup(|app| {
            tauri::async_runtime::spawn(async move {
                control_service
                    .run(async_proc_input_rx, async_proc_output_tx)
                    .await
            });

            let app_handle = app.handle();
            tauri::async_runtime::spawn(async move {
                loop {
                    if let Some(output) = async_proc_output_rx.recv().await {
                        rs2js(output, &app_handle);
                    }
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[tauri::command]
async fn get_control_service_status(
    state: tauri::State<'_, AsyncProcInputTx>,
) -> Result<String, String> {
    Ok(state.control_service.get_status().await)
}

#[tauri::command]
async fn js2rs(message: String, state: tauri::State<'_, AsyncProcInputTx>) -> Result<(), String> {
    info!("js2rs {}", message);
    let async_proc_input_tx = state.inner.lock().await;
    async_proc_input_tx
        .send(message)
        .await
        .map_err(|e| e.to_string())
}

// https://rfdonnelly.github.io/posts/tauri-async-rust-process/
// A function that sends a message from Rust to JavaScript via a Tauri Event
fn rs2js<R: tauri::Runtime>(message: String, manager: &impl Manager<R>) {
    manager.emit_all("rs2js", message).unwrap();
}
