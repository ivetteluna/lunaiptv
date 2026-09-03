package com.luna.visorcamarasunv;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class PlaybackActivity extends Activity {
    private static final int BG = Color.rgb(8, 17, 26);
    private static final int PANEL = Color.rgb(15, 31, 45);
    private static final int BLUE = Color.rgb(31, 74, 105);

    private CameraConfig config;
    private int slot;
    private final Calendar chosen = Calendar.getInstance();
    private EditText seconds;
    private Button dateButton;
    private Button timeButton;
    private Spinner duration;
    private TextView startLabel;
    private TextView currentLabel;
    private TextView errorLabel;
    private TextureView texture;
    private ExoPlayer player;
    private long playbackStartEpoch = 0;
    private int durationMinutes = 15;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable positionTick = new Runnable() {
        @Override public void run() {
            if (player != null && playbackStartEpoch > 0) {
                long now = playbackStartEpoch + Math.max(0, player.getCurrentPosition()) / 1000L;
                currentLabel.setText("POSICIÓN ACTUAL: " + fmt(now));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        config = CameraConfig.load(this);
        slot = Math.max(0, Math.min(4, getIntent().getIntExtra("slot", 0)));
        chosen.setTimeInMillis(System.currentTimeMillis() - 5 * 60_000L);
        buildUi();
        enterImmersive();
        handler.post(positionTick);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout controls1 = row();
        controls1.setPadding(dp(8), dp(6), dp(8), dp(4));
        root.addView(controls1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView title = text("Cámara " + (slot + 1) + " · canal " + config.channels[slot], 15);
        controls1.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));

        dateButton = button("FECHA", v -> chooseDate());
        timeButton = button("HORA/MIN", v -> chooseTime());
        controls1.addView(dateButton, weight(0.8f));
        controls1.addView(timeButton, weight(0.8f));

        seconds = new EditText(this);
        seconds.setHint("seg");
        seconds.setText(String.valueOf(chosen.get(Calendar.SECOND)));
        seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        seconds.setTextColor(Color.WHITE);
        seconds.setHintTextColor(Color.GRAY);
        seconds.setGravity(Gravity.CENTER);
        controls1.addView(seconds, weight(0.45f));

        duration = new Spinner(this);
        String[] vals = new String[]{"5 min", "15 min", "30 min", "60 min"};
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, vals);
        duration.setAdapter(a);
        duration.setSelection(1);
        controls1.addView(duration, weight(0.7f));

        Button play = button("REPRODUCIR", v -> playChosen());
        controls1.addView(play, weight(0.9f));
        Button close = button("VOLVER", v -> finish());
        controls1.addView(close, weight(0.65f));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(4), dp(12), dp(4));
        info.setBackgroundColor(PANEL);
        startLabel = text("INICIO DE REPRODUCCIÓN: —", 18);
        startLabel.setTextColor(Color.rgb(130, 205, 255));
        currentLabel = text("POSICIÓN ACTUAL: —", 16);
        errorLabel = text("", 13);
        errorLabel.setTextColor(Color.rgb(255, 135, 135));
        info.addView(startLabel);
        info.addView(currentLabel);
        info.addView(errorLabel);
        root.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        texture = new TextureView(this);
        texture.setBackgroundColor(Color.BLACK);
        root.addView(texture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = row();
        bottom.setPadding(dp(6), dp(4), dp(6), dp(6));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        bottom.addView(button("⏸/▶", v -> togglePause()), weight(0.8f));
        bottom.addView(button("-60 s", v -> jump(-60)), weight(0.75f));
        bottom.addView(button("-10 s", v -> jump(-10)), weight(0.75f));
        bottom.addView(button("-1 s", v -> jump(-1)), weight(0.7f));
        bottom.addView(button("+1 s", v -> jump(1)), weight(0.7f));
        bottom.addView(button("+10 s", v -> jump(10)), weight(0.75f));
        bottom.addView(button("+60 s", v -> jump(60)), weight(0.75f));
        bottom.addView(button("FOTO", v -> snapshot()), weight(0.75f));

        updateDateTimeButtons();
        play.requestFocus();
    }

    private void playChosen() {
        try {
            int s = Integer.parseInt(seconds.getText().toString().trim());
            if (s < 0 || s > 59) throw new NumberFormatException();
            chosen.set(Calendar.SECOND, s);
            chosen.set(Calendar.MILLISECOND, 0);
            String d = String.valueOf(duration.getSelectedItem());
            durationMinutes = d.startsWith("5 ") ? 5 : d.startsWith("30") ? 30 : d.startsWith("60") ? 60 : 15;
            startReplay(chosen.getTimeInMillis() / 1000L);
        } catch (Exception e) {
            Toast.makeText(this, "Segundo válido: 0 a 59", Toast.LENGTH_SHORT).show();
        }
    }

    private void startReplay(long beginEpoch) {
        playbackStartEpoch = beginEpoch;
        long end = beginEpoch + durationMinutes * 60L;
        startLabel.setText("INICIO DE REPRODUCCIÓN: " + fmt(beginEpoch) + "   ·   HASTA: " + fmt(end));
        currentLabel.setText("POSICIÓN ACTUAL: " + fmt(beginEpoch));
        errorLabel.setText("Conectando al histórico…");
        releasePlayer();
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(1000, 5000, 500, 1000).build();
        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        player.setVideoTextureView(texture);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) errorLabel.setText("Reproduciendo desde " + fmt(playbackStartEpoch));
            }
            @Override public void onPlayerError(PlaybackException error) {
                errorLabel.setText("No se pudo reproducir: " + (error.getMessage() == null ? "error RTSP" : error.getMessage()));
            }
        });
        String url = config.replayUrl(slot, beginEpoch, end);
        RtspMediaSource source = new RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url));
        player.setMediaSource(source);
        player.prepare();
        player.play();
    }

    private void jump(int secondsDelta) {
        if (playbackStartEpoch <= 0) return;
        long current = playbackStartEpoch + (player == null ? 0 : Math.max(0, player.getCurrentPosition()) / 1000L);
        long target = Math.max(1, current + secondsDelta);
        chosen.setTimeInMillis(target * 1000L);
        seconds.setText(String.valueOf(chosen.get(Calendar.SECOND)));
        updateDateTimeButtons();
        startReplay(target);
    }

    private void togglePause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
    }

    private void chooseDate() {
        new DatePickerDialog(this, (v, y, m, d) -> {
            chosen.set(Calendar.YEAR, y); chosen.set(Calendar.MONTH, m); chosen.set(Calendar.DAY_OF_MONTH, d);
            updateDateTimeButtons();
        }, chosen.get(Calendar.YEAR), chosen.get(Calendar.MONTH), chosen.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void chooseTime() {
        new TimePickerDialog(this, (v, h, m) -> {
            chosen.set(Calendar.HOUR_OF_DAY, h); chosen.set(Calendar.MINUTE, m);
            updateDateTimeButtons();
        }, chosen.get(Calendar.HOUR_OF_DAY), chosen.get(Calendar.MINUTE), true).show();
    }

    private void updateDateTimeButtons() {
        dateButton.setText(new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(chosen.getTime()));
        timeButton.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(chosen.getTime()));
    }

    private void snapshot() {
        if (!texture.isAvailable()) {
            Toast.makeText(this, "Todavía no hay imagen", Toast.LENGTH_SHORT).show(); return;
        }
        Bitmap bmp = texture.getBitmap();
        if (bmp == null) return;
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Historico");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "Historico_C" + (slot + 1) + "_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 94, fos);
            Toast.makeText(this, "Foto guardada: " + out.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar", Toast.LENGTH_SHORT).show();
        }
    }

    private String fmt(long epoch) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(epoch * 1000L));
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackgroundColor(BG);
        return l;
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setAllCaps(false); b.setFocusable(true);
        b.setOnClickListener(click); b.setBackgroundColor(BLUE);
        return b;
    }

    private TextView text(String text, int size) {
        TextView t = new TextView(this); t.setText(text); t.setTextColor(Color.WHITE); t.setTextSize(size); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        p.setMargins(dp(3), 0, dp(3), 0); return p;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            finish(); return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(positionTick);
        releasePlayer();
        super.onDestroy();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
