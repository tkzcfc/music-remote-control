<script setup lang="ts">

import { ref } from "vue";
import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/tauri";

const serverAdress = ref("");
const serviceStatus = ref("Disconnected");
const clientToken = ref("");
const authorizationCode = ref("");

serverAdress.value = localStorage.getItem("serverAdress") || "";
clientToken.value = localStorage.getItem("clientToken") || "";
authorizationCode.value = localStorage.getItem("authorizationCode") || "abc123";

import { toast } from 'vue3-toastify';
import 'vue3-toastify/dist/index.css';


async function update_page_with_service_status() {
  serviceStatus.value = await invoke("get_control_service_status");
}

async function send_message_to_rust(name: string, message: any) {
  const jsonString = JSON.stringify({
    name: name,
    data: JSON.stringify(message),
  });
  console.log("call js2rs: " + jsonString);
  return await invoke('js2rs', { message: jsonString });
}

async function on_click_login() {
  serviceStatus.value = "Connecting"

  // toast.remove("tag_connecting");
  toast(`Connecting to: "${serverAdress.value}"`, {
    // toastId: "tag_connecting",
    position: toast.POSITION.BOTTOM_CENTER,
    type: "info",
  });
  
  await send_message_to_rust("ConnectRequest", {
    addr: serverAdress.value
  });
}

async function on_click_control(action: string) {
  const ACTION_DOWN = 0;
  const ACTION_UP = 1;

  const KEYCODE_MEDIA_NEXT = 87;
  const KEYCODE_MEDIA_PREVIOUS = 88;
  const KEYCODE_MEDIA_PAUSE = 127;
  const KEYCODE_MEDIA_PLAY = 126;

  let request: any = null;
  switch (action) {
    case 'play':
      request = {
        action: ACTION_DOWN,
        code: KEYCODE_MEDIA_PLAY,
      }
      break;
    case 'pause':
      request = {
        action: ACTION_DOWN,
        code: KEYCODE_MEDIA_PAUSE,
      }
      break;
    case 'next':
      request = {
        action: ACTION_DOWN,
        code: KEYCODE_MEDIA_NEXT,
      }
      break;
    case 'previous':
      request = {
        action: ACTION_DOWN,
        code: KEYCODE_MEDIA_PREVIOUS,
      }
      break;
  
    default:
      break;
  }

  if(request == null)
    return;

  request.token = clientToken.value;
  request.authorization_code = authorizationCode.value;
  await send_message_to_rust("SendControlMediaKeyEventRequest", request);

  request.action = ACTION_UP;
  await send_message_to_rust("SendControlMediaKeyEventRequest", request);
}

function save_to_local_storage(key: string) {
  // @ts-ignore
  localStorage.setItem(key, this[key]);
}

await update_page_with_service_status();

await listen('rs2js', (event: any) => {
  console.log("js: rs2js: " + event.payload);

  const pack = JSON.parse(event.payload);
  const name = pack.name;
  const message = JSON.parse(pack.data);
  if (name == "ConnectResponse") {
    if(message.ok) {
      serviceStatus.value = "Connected"
      toast("Connection successful", {
        // toastId: "tag_connect_result",
        position: toast.POSITION.BOTTOM_CENTER,
        type: "success",
      });
    }
    else {
      serviceStatus.value = "Disconnected"
      
      console.log("连接失败");
      toast(`Connection error: "${message.error}"`, {
        // toastId: "tag_connect_result",
        position: toast.POSITION.BOTTOM_CENTER,
        type: "error",
      });
    }
  }
  else if(name == "DisconnectNtf") {
      serviceStatus.value = "Disconnected"
    
      let text;
      if(message.reason == "") {
        text = "Connection disconnected"
      }
      else {
        text = `Connection disconnected, error: "${message.reason}"`
      }
      toast(text, {
        // toastId: "tag_disconnect_ntf",
        position: toast.POSITION.BOTTOM_CENTER,
        type: "warning",
      });
    }
    else if(name == "SendControlMediaKeyEventResponse") {
      if(message.ok) {
        toast(`Successfully  sent`, {
          position: toast.POSITION.BOTTOM_CENTER,
          type: "info",
        });
      }
      else {
        toast(`Sending failed, error: ${message.error}`, {
          position: toast.POSITION.BOTTOM_CENTER,
          type: "error",
        });
      }
    }
});

</script>

<template>
  <!-- 根据服务状态显示不同的内容 -->
  <div v-if="serviceStatus === 'Connected'">
    <div class="input-container">
      <input v-model="clientToken" @input="save_to_local_storage('clientToken')" placeholder="Enter client token" />
      <input v-model="authorizationCode" @input="save_to_local_storage('authorizationCode')" placeholder="Enter authorization code" />
    </div>
    <p></p>
    <div class="button-container">
     <form class="row" @submit.prevent="on_click_control('play')">
       <button type="submit">Play</button>
     </form>
     <form class="row" @submit.prevent="on_click_control('pause')">
       <button type="submit">Pause</button>
     </form>
     <form class="row" @submit.prevent="on_click_control('next')">
       <button type="submit">Next</button>
     </form>
     <form class="row" @submit.prevent="on_click_control('previous')">
       <button type="submit">Previous</button>
     </form>
    </div>
  </div>
  
  <!-- <div v-else-if="serviceStatus === 'Connecting'">
    <p>Loading...</p>
  </div>
   -->
  <div v-else>
    <form class="row" @submit.prevent="on_click_login">
      <input v-model="serverAdress" @input="save_to_local_storage('serverAdress')"  placeholder="Enter server address" />
      <button type="submit">Connect</button>
    </form>
  </div>

</template>


<style scoped>
.button-container {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px; /* 设置按钮间的间距 */
  }
.button-container form {
  margin: 0;
}
.input-container {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px; /* 设置按钮间的间距 */
  }
.input-container form {
  margin: 0;
}
</style>