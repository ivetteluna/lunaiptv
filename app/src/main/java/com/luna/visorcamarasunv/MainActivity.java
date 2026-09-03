package com.luna.visorcamarasunv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_COUNT = 5;
    private static final int BG = Color.rgb(8, 17, 26);
    private static final int PANEL = Color.rgb(15, 31, 45);
    private static final int GREEN = Color.rgb(10, 143, 105);
    private static final int BLUE = Color.rgb(31, 74, 105);
    private static final int YELLOW = Color.rgb(255, 215, 0);

    private LinearLayout root;
    private LinearLayout grid;
    private LinearLayout toolbar;
    private TextView topStatus;
    private final CameraTile[] tiles = new CameraTile[CAMERA_COUNT];
    private final ExoPlayer[] players = new ExoPlayer[CAMERA_COUNT];
    private final int[] currentStream = new int[CAMERA_COUNT];
    private final boolean[] fallbackTried = new boolean[CAMERA_COUNT];
    private CameraConfig config;
    private int selected = 0;
    private boolean audioOn = false;

    private FrameLayout fullOverlay;
    private TextureView fullTexture;
    private TextView fullLabel;
    private int fullscreenIndex = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        config = CameraConfig.load(this);
        buildUi();
        enterImmersive();
        if (config.password == null || config.password.isEmpty()) {
            root.postDelayed(() -> showSettings(true), 300);
        } else {
            root.postDelayed(this::startAll, 250);
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        topStatus = new TextView(this);
        topStatus.setTextColor(Color.WHITE);
        topStatus.setTextSize(15);
        topStatus.setGravity(Gravity.CENTER_VERTICAL);
        topStatus.setPadding(dp(12), 0, dp(12), 0);
        topStatus.setBackgroundColor(PANEL);
        topStatus.setText("Visor Cámaras UNV · Seleccionada: Cámara 1");
        root.addView(topStatus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row1 = makeRow();
        LinearLayout row2 = makeRow();
        grid.addView(row1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        grid.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < CAMERA_COUNT; i++) {
            CameraTile tile = new CameraTile(this, i);
            tiles[i] = tile;
            LinearLayout row = i < 3 ? row1 : row2;
            row.addView(tile.host, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(5), dp(5), dp(5), dp(5));
        toolbar.setBackgroundColor(PANEL);
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        addToolbarButton("EN VIVO", v -> playSlot(selected, config.mosaicSubstream ? 1 : 0, false));
        addToolbarButton("AMPLIAR", v -> enterFullscreen(selected));
        addToolbarButton("REPRODUCIR", v -> openPlayback());
        addToolbarButton("FOTO", v -> takeSnapshot());
        addToolbarButton("AUDIO", v -> toggleAudio());
        addToolbarButton("AJUSTES", v -> showSettings(false));

        selectCamera(0);
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        return row;
    }

    private void addToolbarButton(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setBackground(makeButtonBg(BLUE));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        toolbar.addView(b, lp);
    }

    private GradientDrawable makeButtonBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(7));
        d.setStroke(dp(1), Color.rgb(69, 111, 143));
        return d;
    }

    private void startAll() {
        releasePlayers();
        int stream = config.mosaicSubstream ? 1 : 0;
        for (int i = 0; i < CAMERA_COUNT; i++) playSlot(i, stream, false);
        tiles[0].host.requestFocus();
    }

    private ExoPlayer ensurePlayer(int index) {
        if (players[index] != null) return players[index];
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3500, 500, 900)
                .build();
        ExoPlayer player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVolume(0f);
        player.setVideoTextureView(tiles[index].texture);
        final int idx = index;
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    tiles[idx].setStatus("EN VIVO · " + (currentStream[idx] == 0 ? "MÁXIMA" : "FLUIDA"), true);
                } else if (state == Player.STATE_BUFFERING) {
                    tiles[idx].setStatus("conectando C" + config.channels[idx] + "…", false);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (currentStream[idx] == 0 && !fallbackTried[idx]) {
                    fallbackTried[idx] = true;
                    playSlot(idx, 1, true);
                    return;
                }
                tiles[idx].setStatus("SIN VIDEO · revise C" + config.channels[idx], false);
                if (idx == selected) topStatus.setText("Cámara " + (idx + 1) + " sin video · " + shortError(error));
            }
        });
        players[index] = player;
        return player;
    }

    private String shortError(PlaybackException e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return "error RTSP";
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    private void playSlot(int index, int stream, boolean fromFallback) {
        if (index < 0 || index >= CAMERA_COUNT) return;
        if (!fromFallback) fallbackTried[index] = false;
        currentStream[index] = stream;
        ExoPlayer player = ensurePlayer(index);
        try {
            String url = config.liveUrl(index, stream);
            RtspMediaSource source = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(MediaItem.fromUri(url));
            player.setMediaSource(source);
            player.prepare();
            player.play();
            tiles[index].setStatus("conectando C" + config.channels[index] + "…", false);
        } catch (Exception e) {
            tiles[index].setStatus("SIN VIDEO", false);
        }
    }

    private void selectCamera(int index) {
        selected = Math.max(0, Math.min(CAMERA_COUNT - 1, index));
        for (int i = 0; i < CAMERA_COUNT; i++) tiles[i].applyBorder(i == selected);
        topStatus.setText("Visor Cámaras UNV · Seleccionada: Cámara " + (selected + 1) + " · canal " + config.channels[selected]);
        updateAudioRouting();
    }

    private void toggleAudio() {
        audioOn = !audioOn;
        updateAudioRouting();
        Toast.makeText(this, audioOn ? "Audio de Cámara " + (selected + 1) : "Audio apagado", Toast.LENGTH_SHORT).show();
    }

    private void updateAudioRouting() {
        for (int i = 0; i < CAMERA_COUNT; i++) {
            if (players[i] != null) players[i].setVolume(audioOn && i == selected ? 1f : 0f);
        }
    }

    private void enterFullscreen(int index) {
        if (fullscreenIndex >= 0) return;
        selectCamera(index);
        fullscreenIndex = index;
        fullOverlay = new FrameLayout(this);
        fullOverlay.setBackgroundColor(Color.BLACK);
        fullOverlay.setFocusable(true);
        fullOverlay.setFocusableInTouchMode(true);

        fullTexture = new TextureView(this);
        fullOverlay.addView(fullTexture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fullLabel = new TextView(this);
        fullLabel.setTextColor(Color.WHITE);
        fullLabel.setTextSize(18);
        fullLabel.setPadding(dp(16), dp(10), dp(16), dp(10));
        fullLabel.setBackgroundColor(0x77000000);
        fullLabel.setText("Cámara " + (index + 1) + " · canal " + config.channels[index] + " · MENU: etiqueta · ATRÁS: volver");
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        fullOverlay.addView(fullLabel, labelLp);

        root.addView(fullOverlay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        topStatus.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);

        ExoPlayer p = ensurePlayer(index);
        p.clearVideoTextureView(tiles[index].texture);
        p.setVideoTextureView(fullTexture);
        fallbackTried[index] = false;
        playSlot(index, 0, false);
        fullOverlay.requestFocus();
    }

    private void exitFullscreen() {
        if (fullscreenIndex < 0) return;
        int idx = fullscreenIndex;
        ExoPlayer p = players[idx];
        if (p != null) {
            p.clearVideoTextureView(fullTexture);
            p.setVideoTextureView(tiles[idx].texture);
        }
        root.removeView(fullOverlay);
        fullOverlay = null;
        fullTexture = null;
        fullscreenIndex = -1;
        topStatus.setVisibility(View.VISIBLE);
        grid.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        playSlot(idx, config.mosaicSubstream ? 1 : 0, false);
        tiles[idx].host.requestFocus();
        enterImmersive();
    }

    private void openPlayback() {
        Intent intent = new Intent(this, PlaybackActivity.class);
        intent.putExtra("slot", selected);
        startActivity(intent);
    }

    private void takeSnapshot() {
        TextureView view = fullscreenIndex >= 0 ? fullTexture : tiles[selected].texture;
        if (view == null || !view.isAvailable()) {
            Toast.makeText(this, "La cámara todavía no tiene imagen", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = view.getBitmap();
        if (bmp == null) {
            Toast.makeText(this, "No se pudo obtener el fotograma", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Capturas");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date());
        File out = new File(dir, "Camara_" + (selected + 1) + "_" + stamp + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 94, fos);
            Toast.makeText(this, "Foto guardada: " + out.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar la foto", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSettings(boolean firstRun) {
        CameraConfig current = CameraConfig.load(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(12), dp(22), dp(12));

        EditText host = field("IP del NVR", current.host, false);
        EditText port = field("Puerto RTSP", String.valueOf(current.rtspPort), true);
        EditText user = field("Usuario", current.username, false);
        EditText pass = field("Contraseña", current.password, false);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(host); form.addView(port); form.addView(user); form.addView(pass);

        TextView channelTitle = label("Canales del NVR para Cámara 1 a 5");
        form.addView(channelTitle);
        EditText[] ch = new EditText[CAMERA_COUNT];
        for (int i = 0; i < CAMERA_COUNT; i++) {
            ch[i] = field("Cámara " + (i + 1) + " → canal NVR", String.valueOf(current.channels[i]), true);
            form.addView(ch[i]);
        }
        CheckBox sub = new CheckBox(this);
        sub.setText("Usar subflujo en mosaico (recomendado para Android TV)");
        sub.setTextColor(Color.WHITE);
        sub.setChecked(current.mosaicSubstream);
        form.addView(sub);

        TextView note = label("La app usa RTSP H.264 de Android Media3. Si un flujo principal no abre, vuelve automáticamente al subflujo.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(12);
        form.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Configuración · Visor Cámaras UNV")
                .setView(scroll)
                .setPositiveButton("GUARDAR Y CONECTAR", null)
                .setNegativeButton(firstRun ? "SALIR" : "CANCELAR", (d, w) -> { if (firstRun) finish(); })
                .create();
        dlg.setOnShowListener(v -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            try {
                String h = host.getText().toString().trim();
                String u = user.getText().toString().trim();
                String pw = pass.getText().toString();
                int rp = Integer.parseInt(port.getText().toString().trim());
                if (h.isEmpty() || u.isEmpty() || pw.isEmpty() || rp < 1 || rp > 65535) throw new IllegalArgumentException();
                current.host = h; current.username = u; current.password = pw; current.rtspPort = rp;
                for (int i = 0; i < CAMERA_COUNT; i++) {
                    int cc = Integer.parseInt(ch[i].getText().toString().trim());
                    if (cc < 1 || cc > 64) throw new IllegalArgumentException();
                    current.channels[i] = cc;
                }
                current.mosaicSubstream = sub.isChecked();
                current.save(this);
                config = current;
                dlg.dismiss();
                startAll();
            } catch (Exception ex) {
                Toast.makeText(this, "Revise IP, puerto, usuario, clave y canales", Toast.LENGTH_LONG).show();
            }
        }));
        dlg.show();
    }

    private EditText field(String hint, String value, boolean number) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        e.setTextSize(16);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setPadding(dp(10), dp(9), dp(10), dp(9));
        return e;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, dp(6));
        return t;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (fullscreenIndex >= 0 && key == KeyEvent.KEYCODE_BACK) {
                exitFullscreen();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                if (fullscreenIndex >= 0) {
                    if (fullLabel != null) fullLabel.setVisibility(fullLabel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                } else {
                    toolbar.setVisibility(toolbar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
                return true;
            }
            if (fullscreenIndex < 0 && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
                View f = getCurrentFocus();
                for (int i = 0; i < CAMERA_COUNT; i++) {
                    if (f == tiles[i].host || isDescendant(tiles[i].host, f)) {
                        enterFullscreen(i);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isDescendant(ViewGroup parent, View child) {
        if (child == null) return false;
        View v = child;
        while (v != null && v.getParent() instanceof View) {
            if (v.getParent() == parent) return true;
            v = (View) v.getParent();
        }
        return false;
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void releasePlayers() {
        for (int i = 0; i < CAMERA_COUNT; i++) {
            if (players[i] != null) {
                try { players[i].release(); } catch (Exception ignored) {}
                players[i] = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayers();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class CameraTile {
        final int index;
        final FrameLayout host;
        final TextureView texture;
        final TextView status;

        CameraTile(Activity context, int index) {
            this.index = index;
            host = new FrameLayout(context);
            host.setBackgroundColor(Color.BLACK);
            host.setPadding(dp(3), dp(3), dp(3), dp(3));
            host.setFocusable(true);
            host.setFocusableInTouchMode(true);

            texture = new TextureView(context);
            texture.setOpaque(true);
            texture.setBackgroundColor(Color.BLACK);
            host.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            status = new TextView(context);
            status.setText("Cámara " + (index + 1));
            status.setTextColor(Color.WHITE);
            status.setTextSize(13);
            status.setGravity(Gravity.CENTER);
            status.setBackgroundColor(PANEL);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.BOTTOM);
            host.addView(status, slp);

            host.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) selectCamera(index); });
            host.setOnClickListener(v -> {
                selectCamera(index);
                enterFullscreen(index);
            });
            applyBorder(index == 0);
        }

        void applyBorder(boolean isSelected) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(Color.BLACK);
            d.setStroke(dp(isSelected ? 3 : 1), isSelected ? YELLOW : Color.rgb(31, 57, 76));
            host.setBackground(d);
        }

        void setStatus(String msg, boolean good) {
            runOnUiThread(() -> {
                status.setText("Cámara " + (index + 1) + " · " + msg);
                status.setBackgroundColor(good ? GREEN : PANEL);
            });
        }
    }
}
