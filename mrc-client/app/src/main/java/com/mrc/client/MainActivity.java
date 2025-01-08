package com.mrc.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


class ConnectionStatus {
    public final static int CONNECTING = 0;
    public final static int CONNECTED = 1;
    public final static int DISCONNECTING = 2;
    public final static int DISCONNECTED = 3;
}

public class MainActivity extends AppCompatActivity {
    public final static String TAG = "MainActivity";
    // Used to load the 'client' library on application startup.
    static {
        System.loadLibrary("client");
    }

    TcpClient client = new TcpClient();

    TextView textViewStatus;
    EditText editTextToken;
    EditText editTextServerAddress;
    Button buttonConnect;
    Button buttonDisconnect;

    // 是否处于前台
    AtomicBoolean isForeground = new AtomicBoolean(true);
    // 连接id
    AtomicInteger connectionId = new AtomicInteger(-1);

    // 服务器地址
    String serverIpAddress;
    int serverPort;
    // token
    String token;

    AtomicInteger curStatus = new AtomicInteger(ConnectionStatus.DISCONNECTED);

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

        textViewStatus = findViewById(R.id.textViewStatus);
        editTextToken = findViewById(R.id.editTextToken);
        editTextServerAddress = findViewById(R.id.editTextServerAddress);
        buttonConnect = findViewById(R.id.buttonConnect);
        buttonDisconnect = findViewById(R.id.buttonDisconnect);

        client.setMessageReceivedListener((event_type, connection_id, data) -> {
            if (connectionId.get() != connection_id){
                return;
            }

            // 自动重连
            if(!isForeground.get()) {
                if (event_type == TcpClient.EVENT_ON_DISCONNECT || event_type == TcpClient.EVENT_ON_CONNECT_FAILED) {
                    changeStatus(ConnectionStatus.CONNECTING);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }

                    connectionId.set(client.nextConnectionId());
                    client.connect(serverIpAddress, serverPort, 5);
                    return;
                }
            }

            if (event_type == TcpClient.EVENT_ON_RECV_DATA) {
                try {
                    String msg = new String(data, StandardCharsets.UTF_8);
                    Log.i(TAG, "recv:" + msg);
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
                            if (token.equals(keyEvent.token)) {
                                // https://stackoverflow.com/questions/18800198/control-the-default-music-player-of-android-or-any-other-music-player#comment137646196_53961746
                                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                                if (audioManager != null) {
                                    audioManager.dispatchMediaKeyEvent(new KeyEvent(keyEvent.action, keyEvent.code));
                                    //audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                                }
                            }
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
                            if (isForeground.get()) {
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

        loadText();
        updateUI();
    }

    static void sendMessage(TcpClient client, int connectionId, Object data) {
        Gson gson = new Gson();
        Message message = new Message();
        message.name = data.getClass().getSimpleName();
        message.data = gson.toJson(data);

        byte[] jsonBytes = gson.toJson(message).getBytes();

        ByteBuffer buffer = ByteBuffer.allocate(4 + jsonBytes.length);
        buffer.putInt(jsonBytes.length);
        buffer.put(jsonBytes);
        client.send(connectionId, buffer.array());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 应用程序回到前台
        isForeground.set(true);
        updateUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 应用程序进入后台
        isForeground.set(false);
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
        token = editTextToken.getText().toString();

        saveText();
        changeStatus(ConnectionStatus.CONNECTING);
        connectionId.set(client.nextConnectionId());
        client.connect(serverIpAddress, serverPort, 5);
    }

    public void onButtonClickDisconnect(View view) {
        changeStatus(ConnectionStatus.DISCONNECTING);
        client.disconnect(connectionId.get());
    }

    public void changeStatus(int status) {
        if(curStatus.get() != status) {
            curStatus.set(status);
            if(isForeground.get()) {
                updateUI();
            }
        }
    }

    @SuppressLint("SetTextI18n")
    public void updateUI() {
        int status = curStatus.get();
        editTextServerAddress.setEnabled(status == ConnectionStatus.DISCONNECTED);
        editTextToken.setEnabled(status == ConnectionStatus.DISCONNECTED);

        switch (status) {
            case ConnectionStatus.CONNECTING:
                buttonConnect.setEnabled(false);
                buttonDisconnect.setEnabled(false);
                textViewStatus.setText("connecting");
                break;
            case ConnectionStatus.CONNECTED:
                buttonConnect.setEnabled(false);
                buttonDisconnect.setEnabled(true);
                textViewStatus.setText("connected");
                break;
            case ConnectionStatus.DISCONNECTING:
                buttonConnect.setEnabled(false);
                buttonDisconnect.setEnabled(false);
                textViewStatus.setText("disconnecting");
                break;
            case ConnectionStatus.DISCONNECTED:
                buttonConnect.setEnabled(true);
                buttonDisconnect.setEnabled(false);
                textViewStatus.setText("disconnected");
                break;
        }
    }

    private void saveText() {
        String serverAddress = editTextServerAddress.getText().toString().trim();
        String token = editTextToken.getText().toString();

        SharedPreferences sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("input_serverAddress", serverAddress);
        editor.putString("input_token", token);
        editor.apply();
    }

    private void loadText() {
        SharedPreferences sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE);
        String serverAddress = sharedPreferences.getString("input_serverAddress", "");
        String token = sharedPreferences.getString("input_token", "");
        editTextServerAddress.setText(serverAddress);
        editTextToken.setText(token);
    }
}