package com.cleantv.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PhonePortalActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        PhoneServer server = PhoneServer.getInstance(this);
        try {
            if (!server.isAlive()) {
                server.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String portalUrl = server.getPortalUrl();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.argb(220, 15, 18, 24)); // tvOS Frosted Dark background

        // Card container
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(80, 50, 80, 50);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(240, 30, 35, 45));
        cardBg.setCornerRadius(32f);
        card.setBackground(cardBg);

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText("手机扫码管理桌面");
        tvTitle.setTextSize(26f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTypeface(Typefaces.get(this));
        tvTitle.setPadding(0, 0, 0, 30);
        card.addView(tvTitle);

        // QR Code Image
        ImageView ivQr = new ImageView(this);
        Bitmap qrBitmap = QrCodeHelper.generateQrCode(portalUrl, 360, 360);
        if (qrBitmap != null) {
            ivQr.setImageBitmap(qrBitmap);
        }
        card.addView(ivQr, new LinearLayout.LayoutParams(360, 360));

        // Subtitle & URL
        TextView tvSub = new TextView(this);
        tvSub.setText("手机连入同一 Wi-Fi 即可扫码打开");
        tvSub.setTextSize(16f);
        tvSub.setTextColor(Color.argb(200, 255, 255, 255));
        tvSub.setPadding(0, 20, 0, 8);
        card.addView(tvSub);

        TextView tvUrl = new TextView(this);
        tvUrl.setText(portalUrl);
        tvUrl.setTextSize(18f);
        tvUrl.setTextColor(Color.parseColor("#34C759")); // Apple Green
        card.addView(tvUrl);

        TextView tvTip = new TextView(this);
        tvTip.setText("按遥控器【返回键】关闭");
        tvTip.setTextSize(14f);
        tvTip.setTextColor(Color.argb(140, 255, 255, 255));
        tvTip.setPadding(0, 24, 0, 0);
        card.addView(tvTip);

        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
