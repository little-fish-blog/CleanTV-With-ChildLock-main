package com.cleantv.launcher;

public class AerialVideo {
    public final String id;
    public final String label;
    public final String category;
    public final String url;   // 2K AVC (H.264), maximum compatibility for Android 5.0+
    public final String hevc;  // 2K HEVC
    public final String uhd;   // 4K HEVC

    public AerialVideo(String id, String label, String category, String url, String hevc, String uhd) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.url = url;
        this.hevc = hevc;
        this.uhd = uhd;
    }

    /**
     * Choose optimal stream URL based on Android API level and configuration.
     * Android 5.0 (API 21/22) old chipsets usually struggle with 4K HEVC in software fallback,
     * so 2K AVC (H.264) is chosen as default rock-solid stream.
     */
    public String getPlaybackUrl(boolean preferUhd) {
        if (preferUhd && uhd != null && !uhd.isEmpty()) {
            return uhd;
        }
        return (url != null && !url.isEmpty()) ? url : hevc;
    }
}
