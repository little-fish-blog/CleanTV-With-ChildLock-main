package com.cleantv.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.TextView;

public class AerialSaverActivity extends Activity implements SurfaceHolder.Callback, MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private static final String TAG = "CleanTV.AerialSaver";

    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private MediaPlayer mMediaPlayer;
    private TextView mTvLocation;
    private FrameLayout mRoot;

    private AerialManager mManager;
    private AerialVideo mCurrentVideo;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPreferUhd = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mManager = AerialManager.getInstance(this);
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        mPreferUhd = "4k".equalsIgnoreCase(prefs.getString(Config.KEY_AERIAL_RESOLUTION, "2k"));

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.BLACK);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mRoot.addView(mSurfaceView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Location text in bottom-right corner, tvOS style
        mTvLocation = new TextView(this);
        mTvLocation.setTextColor(Color.WHITE);
        mTvLocation.setTextSize(20f);
        mTvLocation.setShadowLayer(4f, 2f, 2f, Color.argb(180, 0, 0, 0));
        mTvLocation.setPadding(0, 0, 60, 40);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        textLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        mRoot.addView(mTvLocation, textLp);

        setContentView(mRoot);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceHolder = holder;
        playCurrentOrRandom();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceHolder = null;
        releasePlayer();
    }

    private void playCurrentOrRandom() {
        mCurrentVideo = mManager.getCurrentOrRandom();
        playVideo(mCurrentVideo);
    }

    private void playVideo(AerialVideo video) {
        if (video == null || mSurfaceHolder == null) return;
        releasePlayer();

        try {
            String playUrl = video.getPlaybackUrl(mPreferUhd);
            Log.i(TAG, "Playing aerial video: " + video.label + " -> " + playUrl);

            mTvLocation.setText(video.label);
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(800);
            mTvLocation.startAnimation(fadeIn);

            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDisplay(mSurfaceHolder);
            mMediaPlayer.setDataSource(this, Uri.parse(playUrl));
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnErrorListener(this);
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error playing video: " + e.getMessage());
            nextVideo();
        }
    }

    private void nextVideo() {
        mCurrentVideo = mManager.getNext();
        playVideo(mCurrentVideo);
    }

    private void prevVideo() {
        mCurrentVideo = mManager.getPrevious();
        playVideo(mCurrentVideo);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        nextVideo();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.w(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra + "; switching to next.");
        mHandler.postDelayed(this::nextVideo, 1000);
        return true;
    }

    private void releasePlayer() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
                mMediaPlayer.reset();
                mMediaPlayer.release();
            } catch (Exception ignored) {}
            mMediaPlayer = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                nextVideo();
                return true;
            } else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                prevVideo();
                return true;
            } else {
                // Any other key (BACK, HOME, MENU, OK) dismisses screensaver
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }
}
