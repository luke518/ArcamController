package com.luke518.arcamcontroller;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PORT = 50000;
    private static final byte ST = 0x21;
    private static final byte ZN = 0x01;
    private static final byte ET = 0x0D;

    private EditText ipInput;
    private TextView statusText, volumeText, sourceText, modeText;
    private SeekBar volumeSeekBar;
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Handler mainHandler;
    private ExecutorService executor;
    private SharedPreferences prefs;
    private boolean connected = false;

    private static final String[] SOURCES = {
        "DVD", "BD", "STB", "GAME", "AUX", "CD",
        "PHONO", "TUNER", "NET", "USB", "BT", "HDMI 6", "HDMI 7"
    };
    private static final byte[] SOURCE_CODES = {
        0x01, 0x02, 0x03, 0x04, 0x08, 0x09,
        0x0A, 0x0B, 0x0E, 0x0F, 0x10, 0x05, 0x06
    };

    private static final String[] MODES = {
        "Stereo", "Dolby Surround", "DTS Neural:X",
        "Dolby Atmos", "Auro-3D", "Direct", "Stereo Direct"
    };
    private static final byte[] MODE_RC5 = {
        0x21, 0x28, 0x29, 0x2A, 0x2B, 0x22, 0x23
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        prefs = getSharedPreferences("arcam", MODE_PRIVATE);

        ipInput = findViewById(R.id.ipInput);
        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        sourceText = findViewById(R.id.sourceText);
        modeText = findViewById(R.id.modeText);
        volumeSeekBar = findViewById(R.id.volumeSeekBar);

        ipInput.setText(prefs.getString("ip", "192.168.1."));

        findViewById(R.id.btnConnect).setOnClickListener(v -> toggleConnect());
        findViewById(R.id.btnPowerOn).setOnClickListener(v -> sendCommand(new byte[]{ST, ZN, 0x00, 0x01, 0x01, ET}));
        findViewById(R.id.btnPowerOff).setOnClickListener(v -> sendCommand(new byte[]{ST, ZN, 0x00, 0x01, 0x00, ET}));
        findViewById(R.id.btnVolUp).setOnClickListener(v -> sendRC5(0x10, 0x10));
        findViewById(R.id.btnVolDown).setOnClickListener(v -> sendRC5(0x10, 0x11));
        findViewById(R.id.btnMute).setOnClickListener(v -> sendRC5(0x10, 0x0D));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> requestStatus());
        findViewById(R.id.btnMenu).setOnClickListener(v -> sendRC5(0x10, 0x52));
        findViewById(R.id.btnUp).setOnClickListener(v -> sendRC5(0x10, 0x58));
        findViewById(R.id.btnDown).setOnClickListener(v -> sendRC5(0x10, 0x59));
        findViewById(R.id.btnLeft).setOnClickListener(v -> sendRC5(0x10, 0x5A));
        findViewById(R.id.btnRight).setOnClickListener(v -> sendRC5(0x10, 0x5B));
        findViewById(R.id.btnOk).setOnClickListener(v -> sendRC5(0x10, 0x57));
        findViewById(R.id.btnBack).setOnClickListener(v -> sendRC5(0x10, 0x53));

        volumeSeekBar.setMax(99);
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) setVolume(progress);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        Spinner sourceSpinner = findViewById(R.id.sourceSpinner);
        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, SOURCES);
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(sourceAdapter);
        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                selectSource(pos);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        Spinner modeSpinner = findViewById(R.id.modeSpinner);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, MODES);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                sendRC5(0x10, MODE_RC5[pos] & 0xFF);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void toggleConnect() {
        if (connected) {
            disconnect();
        } else {
            String ip = ipInput.getText().toString().trim();
            prefs.edit().putString("ip", ip).apply();
            connect(ip);
        }
    }

    private void connect(String ip) {
        setStatus("Connecting...");
        executor.execute(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, PORT), 3000);
                socket.setSoTimeout(3000);
                out = socket.getOutputStream();
                in = socket.getInputStream();
                connected = true;
                mainHandler.post(() -> {
                    setStatus("Connected to " + ip);
                    ((Button) findViewById(R.id.btnConnect)).setText("Disconnect");
                });
                requestStatus();
                startListener();
            } catch (Exception e) {
                mainHandler.post(() -> setStatus("Failed: " + e.getMessage()));
            }
        });
    }

    private void disconnect() {
        executor.execute(() -> {
            try {
                connected = false;
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
            mainHandler.post(() -> {
                setStatus("Disconnected");
                ((Button) findViewById(R.id.btnConnect)).setText("Connect");
            });
        });
    }

    private void sendCommand(byte[] cmd) {
        if (!connected) { setStatus("Not connected"); return; }
        executor.execute(() -> {
            try {
                out.write(cmd);
                out.flush();
            } catch (Exception e) {
                mainHandler.post(() -> setStatus("Send error: " + e.getMessage()));
            }
        });
    }

    private void sendRC5(int system, int command) {
        sendCommand(new byte[]{ST, ZN, 0x08, 0x02, (byte) system, (byte) command, ET});
    }

    private void setVolume(int vol) {
        sendCommand(new byte[]{ST, ZN, 0x0D, 0x02, ZN, (byte) vol, ET});
        mainHandler.post(() -> volumeText.setText("Volume: " + vol));
    }

    private void selectSource(int index) {
        sendCommand(new byte[]{ST, ZN, 0x1D, 0x01, SOURCE_CODES[index], ET});
        mainHandler.post(() -> sourceText.setText("Source: " + SOURCES[index]));
    }

        sendCommand(new byte[]{ST, ZN, 0x01, 0x01, (byte) brightness, ET});
    }

    private void requestStatus() {
        sendCommand(new byte[]{ST, ZN, 0x00, 0x01, (byte) 0xF0, ET});
        sendCommand(new byte[]{ST, ZN, 0x0D, 0x01, (byte) 0xF0, ET});
        sendCommand(new byte[]{ST, ZN, 0x1D, 0x01, (byte) 0xF0, ET});
    }

    private void startListener() {
        new Thread(() -> {
            byte[] buf = new byte[256];
            while (connected) {
                try {
                    int len = in.read(buf);
                    if (len > 0) parseResponse(buf, len);
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (connected) {
                        connected = false;
                        mainHandler.post(() -> setStatus("Connection lost"));
                    }
                    break;
                }
            }
        }).start();
    }

    private void parseResponse(byte[] buf, int len) {
        if (len < 6) return;
        byte cc = buf[2];
        byte ac = buf[3];
        if (ac != 0x00) return;
        switch (cc) {
            case 0x00:
                boolean on = buf[5] == 0x01;
                mainHandler.post(() -> setStatus(on ? "Power: ON" : "Power: Standby"));
                break;
            case 0x0D:
                if (len > 6) {
                    int vol = buf[6] & 0xFF;
                    mainHandler.post(() -> {
                        volumeText.setText("Volume: " + vol);
                        volumeSeekBar.setProgress(vol);
                    });
                }
                break;
            case 0x1D:
                if (len > 5) {
                    int srcCode = buf[5] & 0xFF;
                    String srcName = "Unknown";
                    for (int i = 0; i < SOURCE_CODES.length; i++) {
                        if ((SOURCE_CODES[i] & 0xFF) == srcCode) {
                            srcName = SOURCES[i];
                            break;
                        }
                    }
                    final String s = srcName;
                    mainHandler.post(() -> sourceText.setText("Source: " + s));
                }
                break;
            case 0x43:
                if (len > 5) {
                    mainHandler.post(() -> modeText.setText("Format: " + getAudioFormat(buf[5] & 0xFF)));
                }
                break;
        }
    }

    private String getAudioFormat(int code) {
        switch (code) {
            case 0x00: return "PCM";
            case 0x01: return "Analogue Direct";
            case 0x02: return "Dolby Digital";
            case 0x03: return "Dolby TrueHD";
            case 0x04: return "Dolby Atmos";
            case 0x05: return "DTS";
            case 0x06: return "DTS-HD";
            case 0x07: return "DTS:X";
            case 0x08: return "Auro-3D";
            default: return "Unknown (0x" + Integer.toHexString(code) + ")";
        }
    }

    private void setStatus(String msg) {
        statusText.setText(msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        executor.shutdown();
    }
}
