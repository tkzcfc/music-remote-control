package com.mrc.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.gson.Gson;
import com.mrc.client.proto.Message;
import com.mrc.client.proto.Ping;
import com.mrc.client.proto.Pong;
import com.mrc.client.proto.PushMediaKeyEvent;

import java.nio.charset.StandardCharsets;

enum ConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
}

public class MainActivity extends AppCompatActivity {
    public final static String TAG = "MainActivity";
    // Used to load the 'client' library on application startup.
    static {
        System.loadLibrary("client");
    }

    TcpClient client = new TcpClient();

    EditText editTextServerAddress;
    TextView textViewStatus;
    Button buttonConnect;
    Button buttonDisconnect;

    // 是否处于前台
    boolean isForeground = true;
    // 连接id
    int connectionId = -1;

    String serverIpAddress;
    int serverPort;

    private MyAccessibilityService myAccessibilityService;


    ConnectionStatus curStatus = ConnectionStatus.DISCONNECTED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        myAccessibilityService = new MyAccessibilityService();

        editTextServerAddress = findViewById(R.id.editTextServerAddress);
        buttonConnect = findViewById(R.id.buttonConnect);
        buttonDisconnect = findViewById(R.id.buttonDisconnect);

        textViewStatus = findViewById(R.id.textViewStatus);

        client.setMessageReceivedListener((event_type, connection_id, data) -> {
            if (connectionId != connection_id){
                return;
            }

            // 自动重连
            if(!isForeground) {
                if (event_type == TcpClient.EVENT_ON_DISCONNECT || event_type == TcpClient.EVENT_ON_CONNECT_FAILED) {
                    changeStatus(ConnectionStatus.CONNECTING);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }

                    connectionId = client.nextConnectionId();
                    client.connect(serverIpAddress, serverPort, 5);
                    return;
                }
            }

            if (event_type == TcpClient.EVENT_ON_RECV_DATA) {
                try {
                    String msg = new String(data, StandardCharsets.UTF_8);
                    Gson gson = new Gson();
                    Message message =  gson.fromJson(msg, Message.class);

                    switch (message.name) {
                        case "Ping":
                            Ping ping = gson.fromJson(message.data, Ping.class);

                            Pong response = new Pong();
                            response.time = ping.time;
                            MainActivity.sendMessage(client, connection_id, response);
                            break;
                        case "Pong":
                            break;
                        case "PushMediaKeyEvent":
                            PushMediaKeyEvent keyEvent = gson.fromJson(message.data, PushMediaKeyEvent.class);

                            // https://stackoverflow.com/questions/18800198/control-the-default-music-player-of-android-or-any-other-music-player#comment137646196_53961746
                            AudioManager mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                            KeyEvent event = new KeyEvent(keyEvent.action, keyEvent.code);
                            mAudioManager.dispatchMediaKeyEvent(event);
                            break;
                    }
                }
                catch (Exception e) {
                    Log.e(MainActivity.TAG, "Protocol parsing failed: " + e);
                }
            }
            else {
                runOnUiThread(() -> {
                    switch (event_type) {
                        case TcpClient.EVENT_ON_CONNECT_SUCCESS:
                            changeStatus(ConnectionStatus.CONNECTED);
                            break;
                        case TcpClient.EVENT_ON_CONNECT_FAILED:
                            changeStatus(ConnectionStatus.DISCONNECTED);
                            if (isForeground) {
                                try {
                                    Toast.makeText(MainActivity.this, new String(data, StandardCharsets.UTF_8), Toast.LENGTH_SHORT).show();
                                } catch (Exception ignored) {
                                }
                            }
                            break;
                        case TcpClient.EVENT_ON_DISCONNECT:
                            changeStatus(ConnectionStatus.DISCONNECTED);
                            break;
                    }
                });
            }
        });
        client.start(3);

        updateUI();
    }

    static void sendMessage(TcpClient client, int connectionId, Object data) {
        Gson gson = new Gson();
        Message message = new Message();
        message.name = data.getClass().getSimpleName();
        message.data = gson.toJson(data);
        client.send(connectionId, gson.toJson(message).getBytes());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 应用程序回到前台
        isForeground = true;
        updateUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 应用程序进入后台
        isForeground = false;
    }

    public void onButtonClickConnect(View view) {
        String text = editTextServerAddress.getText().toString().trim();
        if(text.isEmpty())
        {
            Toast.makeText(MainActivity.this, "Please enter the server address", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] parts = text.split(":");

        String ipAddress;
        int port = 8530;
        try {
            ipAddress = parts[0];
            if(parts.length > 1) {
                port = Integer.parseInt(parts[1]);
            }
        }
        catch (Exception e){
            Toast.makeText(MainActivity.this, "Please enter the correct format for 'Server Address: Port'", Toast.LENGTH_SHORT).show();
            return;
        }

//        Toast.makeText(MainActivity.this, "Start connecting " + ipAddress + ":" + port, Toast.LENGTH_SHORT).show();

        serverIpAddress = ipAddress;
        serverPort = port;

        changeStatus(ConnectionStatus.CONNECTING);
        connectionId = client.nextConnectionId();
        client.connect(serverIpAddress, serverPort, 5);
    }

    public void onButtonClickDisconnect(View view) {
        changeStatus(ConnectionStatus.DISCONNECTING);
        client.disconnect(connectionId);
    }

    public void changeStatus(ConnectionStatus status) {
        if(curStatus != status) {
            curStatus = status;
            if(isForeground) {
                updateUI();
            }
        }
    }

    @SuppressLint("SetTextI18n")
    public void updateUI() {
        switch (curStatus) {
            case CONNECTING:
                buttonConnect.setEnabled(false);
                buttonDisconnect.setEnabled(false);
                textViewStatus.setText("connecting");
                break;
            case CONNECTED:
                buttonConnect.setEnabled(false);
                buttonDisconnect.setEnabled(true);
                textViewStatus.setText("connected");
                break;
            case DISCONNECTING:
                buttonConnect.setEnabled(false);
                buttonDisconnect.setEnabled(false);
                textViewStatus.setText("disconnecting");
                break;
            case DISCONNECTED:
                buttonConnect.setEnabled(true);
                buttonDisconnect.setEnabled(false);
                textViewStatus.setText("disconnected");
                break;
        }
    }
}