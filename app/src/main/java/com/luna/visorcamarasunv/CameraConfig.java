package com.luna.visorcamarasunv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class CameraConfig {
    public static final String PREFS = "visor_unv";

    public String host;
    public int rtspPort;
    public String username;
    public String password;
    public int[] channels = new int[5];
    public boolean mosaicSubstream;

    public static CameraConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        CameraConfig c = new CameraConfig();
        c.host = p.getString("host", "192.168.1.2");
        c.rtspPort = p.getInt("rtsp_port", 554);
        c.username = p.getString("username", "visorpc");
        c.password = p.getString("password", "");
        int[] defaults = new int[]{1, 2, 3, 4, 8};
        for (int i = 0; i < 5; i++) c.channels[i] = p.getInt("channel_" + i, defaults[i]);
        c.mosaicSubstream = p.getBoolean("mosaic_substream", true);
        return c;
    }

    public void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("host", host);
        e.putInt("rtsp_port", rtspPort);
        e.putString("username", username);
        e.putString("password", password);
        for (int i = 0; i < 5; i++) e.putInt("channel_" + i, channels[i]);
        e.putBoolean("mosaic_substream", mosaicSubstream);
        e.apply();
    }

    private String authBase() {
        String user = Uri.encode(username == null ? "" : username);
        String pass = Uri.encode(password == null ? "" : password);
        return "rtsp://" + user + ":" + pass + "@" + host + ":" + rtspPort;
    }

    public String liveUrl(int slot, int stream) {
        int ch = channels[Math.max(0, Math.min(4, slot))];
        return authBase() + "/unicast/c" + ch + "/s" + stream + "/live";
    }

    public String replayUrl(int slot, long beginEpoch, long endEpoch) {
        int ch = channels[Math.max(0, Math.min(4, slot))];
        return authBase() + "/c" + ch + "/b" + beginEpoch + "/e" + endEpoch + "/replay/";
    }
}
