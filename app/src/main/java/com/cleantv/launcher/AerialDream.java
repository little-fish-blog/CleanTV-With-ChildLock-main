package com.cleantv.launcher;

import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

public class AerialDream extends DreamService implements SurfaceHolder.Callback, MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private static final String TAG = "CleanTV.AerialDream";

    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private MediaPlayer mMediaPlayer;
    private TextView mTvLocation;
    private FrameLayout mRoot;
    private AerialManager mManager;
    private AerialVideo mCurrentVideo;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);

        mManager = AerialManager.getInstance(this);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.BLACK);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mRoot.addView(mSurfaceView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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
    public void onDreamingStarted() {
        super.onDreamingStarted();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        releasePlayer();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceHolder = holder;
        mCurrentVideo = mManager.getCurrentOrRandom();
        playVideo(mCurrentVideo);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceHolder = null;
        releasePlayer();
    }

    private void playVideo(AerialVideo video) {
        if (video == null || mSurfaceHolder == null) return;
        releasePlayer();

        try {
            String playUrl = video.getPlaybackUrl(false);
            mTvLocation.setText(video.label);

            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDisplay(mSurfaceHolder);
            mMediaPlayer.setDataSource(this, Uri.parse(playUrl));
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnErrorListener(this);
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play dream video", e);
        }
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
    public void onPrepared(MediaPlayer mp) {
        mp.start();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        mCurrentVideo = mManager.getNext();
        playVideo(mCurrentVideo);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        mCurrentVideo = mManager.getNext();
        playVideo(mCurrentVideo);
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mCurrentVideo = mManager.getNext();
                playVideo(mCurrentVideo);
                return true;
            } else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                mCurrentVideo = mManager.getPrevious();
                playVideo(mCurrentVideo);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
