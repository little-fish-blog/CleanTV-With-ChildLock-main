package com.cleantv.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class AerialManager {
    private static final String TAG = "CleanTV.Aerial";
    private static AerialManager sInstance;

    private final List<AerialVideo> mVideos = new ArrayList<>();
    private final Random mRandom = new Random();
    private int mCurrentIndex = 0;

    public static synchronized AerialManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new AerialManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private AerialManager(Context context) {
        loadVideos(context);
    }

    private void loadVideos(Context context) {
        try (InputStream is = context.getAssets().open("aerials.json")) {
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            String jsonStr = new String(buffer, 0, read, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(jsonStr);
            JSONArray array = root.optJSONArray("videos");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String id = obj.optString("id");
                    String label = obj.optString("label");
                    String cat = obj.optString("category");
                    String url = obj.optString("url");
                    String hevc = obj.optString("hevc");
                    String uhd = obj.optString("uhd");
                    if (!url.isEmpty() || !hevc.isEmpty() || !uhd.isEmpty()) {
                        mVideos.add(new AerialVideo(id, label, cat, url, hevc, uhd));
                    }
                }
            }
            Collections.shuffle(mVideos);
            Log.i(TAG, "Loaded " + mVideos.size() + " Apple TV aerial screensavers.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load aerials.json", e);
        }
    }

    public List<AerialVideo> getAllVideos() {
        return mVideos;
    }

    public synchronized AerialVideo getCurrentOrRandom() {
        if (mVideos.isEmpty()) return null;
        if (mCurrentIndex < 0 || mCurrentIndex >= mVideos.size()) {
            mCurrentIndex = mRandom.nextInt(mVideos.size());
        }
        return mVideos.get(mCurrentIndex);
    }

    public synchronized AerialVideo getNext() {
        if (mVideos.isEmpty()) return null;
        mCurrentIndex = (mCurrentIndex + 1) % mVideos.size();
        return mVideos.get(mCurrentIndex);
    }

    public synchronized AerialVideo getPrevious() {
        if (mVideos.isEmpty()) return null;
        mCurrentIndex = (mCurrentIndex - 1 + mVideos.size()) % mVideos.size();
        return mVideos.get(mCurrentIndex);
    }
}
