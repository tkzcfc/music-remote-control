package com.mrc.client;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'client' library on application startup.
    static {
        System.loadLibrary("client");
    }

    TcpClient client = new TcpClient();
    EditText editTextServerAddress;
    TextView textViewStatus;

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

        editTextServerAddress = findViewById(R.id.editTextServerAddress);

        textViewStatus = findViewById(R.id.textViewStatus);
        textViewStatus.setText("");

        client.start(1);
    }

    // 回调方法
    public void onButtonClick(View view) {
        view.setEnabled(false);

        String text = editTextServerAddress.getText().toString().trim();
        if(text.isEmpty())
        {
            Toast.makeText(MainActivity.this, "Please enter the server address", Toast.LENGTH_SHORT).show();
            return;
        }




//        client.connect()
    }
}