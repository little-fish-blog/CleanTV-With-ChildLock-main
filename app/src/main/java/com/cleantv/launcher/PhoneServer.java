package com.cleantv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

public class PhoneServer extends NanoHTTPD {
    private static final String TAG = "CleanTV.PhoneServer";
    public static final int DEFAULT_PORT = 8080;

    private final Context mContext;
    private final String mToken;
    private static PhoneServer sInstance;

    public static synchronized PhoneServer getInstance(Context context) {
        if (sInstance == null) {
            SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
            String token = prefs.getString(Config.KEY_PORTAL_TOKEN, null);
            if (token == null) {
                token = UUID.randomUUID().toString().substring(0, 6);
                prefs.edit().putString(Config.KEY_PORTAL_TOKEN, token).apply();
            }
            sInstance = new PhoneServer(context.getApplicationContext(), DEFAULT_PORT, token);
        }
        return sInstance;
    }

    private PhoneServer(Context context, int port, String token) {
        super(port);
        this.mContext = context;
        this.mToken = token;
    }

    public String getToken() {
        return mToken;
    }

    public String getPortalUrl() {
        String ip = getIpAddress(mContext);
        if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
        return "http://" + ip + ":" + getListeningPort() + "/" + mToken + "/";
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // Security check with token prefix
        String prefix = "/" + mToken;
        if (!uri.startsWith(prefix)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden: Invalid token");
        }

        String path = uri.substring(prefix.length());
        if (path.isEmpty() || path.equals("/")) {
            return serveWebPage();
        }

        try {
            if ("/api/state".equals(path) && Method.GET.equals(method)) {
                return serveState();
            } else if ("/api/action".equals(path) && Method.POST.equals(method)) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                return handleAction(postData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling HTTP request: " + path, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
    }

    private Response serveWebPage() {
        try (InputStream is = mContext.getAssets().open("web/index.html")) {
            byte[] bytes = new byte[is.available()];
            int read = is.read(bytes);
            String html = new String(bytes, 0, read, StandardCharsets.UTF_8);
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load web/index.html", e);
            // Fallback lightweight built-in page if assets HTML missing
            String fallback = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>CleanTV 控制台</title>" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<style>body{background:#111;color:#fff;font-family:sans-serif;padding:20px;text-align:center;} " +
                    ".btn{background:#007aff;color:#fff;padding:12px 24px;border-radius:8px;border:none;margin:10px;font-size:16px;}</style></head>" +
                    "<body><h2>CleanTV 电视管理后台</h2><p>设备: " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")</p>" +
                    "<div id='apps'>已连接成功！请在电视端使用遥控器或通过本后台调整排序与设置。</div></body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", fallback);
        }
    }

    private Response serveState() {
        try {
            JSONObject state = new JSONObject();
            state.put("model", Build.MODEL);
            state.put("manufacturer", Build.MANUFACTURER);
            state.put("androidVersion", Build.VERSION.RELEASE);
            state.put("sdk", Build.VERSION.SDK_INT);

            SharedPreferences prefs = mContext.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
            state.put("screensaver", prefs.getInt(Config.KEY_SCREENSAVER_TYPE, Config.SCREENSAVER_AERIAL));
            state.put("bootApp", prefs.getString(Config.KEY_BOOT_APP_PACKAGE, ""));

            // Installed TV / Launcher Apps
            PackageManager pm = mContext.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list == null || list.isEmpty()) {
                intent = new Intent(Intent.ACTION_MAIN, null);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                list = pm.queryIntentActivities(intent, 0);
            }

            JSONArray appsArray = new JSONArray();
            if (list != null) {
                for (ResolveInfo info : list) {
                    JSONObject appObj = new JSONObject();
                    appObj.put("packageName", info.activityInfo.packageName);
                    appObj.put("label", info.loadLabel(pm).toString());
                    appsArray.put(appObj);
                }
            }
            // Child Lock state
            com.cleantv.launcher.childlock.ChildLockStore lockStore =
                    com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext);
            JSONObject childLockJson = new JSONObject();
            childLockJson.put("enabled", lockStore.isChildLockEnabled());
            childLockJson.put("mode", lockStore.getCurrentSessionMode());
            childLockJson.put("antiTamper", lockStore.isAntiTamperEnabled());
            childLockJson.put("todayWatchedMinutes", lockStore.getTodayWatchedSeconds() / 60);
            long cooldownUntil = lockStore.getCooldownUntil();
            long now = System.currentTimeMillis();
            childLockJson.put("isCooldown", cooldownUntil > now);
            childLockJson.put("cooldownRemainSeconds", Math.max(0, (cooldownUntil - now) / 1000));

            JSONArray rulesArray = new JSONArray();
            List<com.cleantv.launcher.childlock.ChildLockRule> rules = lockStore.getRules();
            for (com.cleantv.launcher.childlock.ChildLockRule r : rules) {
                JSONObject rObj = new JSONObject();
                rObj.put("id", r.getId());
                rObj.put("name", r.getName());
                rObj.put("enabled", r.isEnabled());
                rObj.put("startHour", r.getStartHour());
                rObj.put("startMinute", r.getStartMinute());
                rObj.put("endHour", r.getEndHour());
                rObj.put("endMinute", r.getEndMinute());
                rObj.put("singleLimitMinutes", r.getSingleLimitMinutes());
                rObj.put("cooldownMinutes", r.getCooldownMinutes());
                rObj.put("dailyLimitMinutes", r.getDailyLimitMinutes());
                JSONArray daysArr = new JSONArray();
                for (int d : r.getDays()) daysArr.put(d);
                rObj.put("days", daysArr);
                rulesArray.put(rObj);
            }
            childLockJson.put("rules", rulesArray);
            state.put("childLock", childLockJson);

            state.put("apps", appsArray);

            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", state.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleAction(String jsonStr) {
        try {
            if (jsonStr == null || jsonStr.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Empty payload");
            }
            JSONObject req = new JSONObject(jsonStr);
            String action = req.optString("action");
            SharedPreferences prefs = mContext.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);

            if ("set_boot_app".equals(action)) {
                String pkg = req.optString("packageName");
                prefs.edit().putString(Config.KEY_BOOT_APP_PACKAGE, pkg).apply();
            } else if ("set_screensaver".equals(action)) {
                int type = req.optInt("type", Config.SCREENSAVER_AERIAL);
                prefs.edit().putInt(Config.KEY_SCREENSAVER_TYPE, type).apply();
            } else if ("input_text".equals(action)) {
                String text = req.optString("text");
                // Input text via ADB / system input command or clipboard
                try {
                    String escaped = text.replace(" ", "%s").replace("\"", "\\\"");
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "input text \"" + escaped + "\""});
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to exec input text", ex);
                }
            } else if ("send_key".equals(action)) {
                int code = req.optInt("keyCode", 0);
                if (code > 0) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "input keyevent " + code});
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to exec input keyevent", ex);
                    }
                }
            } else if ("childlock_toggle".equals(action)) {
                com.cleantv.launcher.childlock.ChildLockStore lockStore =
                        com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext);
                boolean en = req.optBoolean("enabled", !lockStore.isChildLockEnabled());
                lockStore.setChildLockEnabled(en);
            } else if ("childlock_set_password".equals(action)) {
                String pwd = req.optString("password");
                if (pwd != null && pwd.length() == 4) {
                    com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext).setMasterPassword(pwd);
                }
            } else if ("childlock_switch_mode".equals(action)) {
                String mode = req.optString("mode", com.cleantv.launcher.childlock.ChildLockStore.MODE_ADULT);
                com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext).setCurrentSessionMode(mode);
            } else if ("childlock_reset_cooldown".equals(action)) {
                com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext).clearCooldown();
                com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext).clearTempUnlock();
            } else if ("childlock_save_rules".equals(action)) {
                JSONArray rulesArr = req.optJSONArray("rules");
                if (rulesArr != null) {
                    List<com.cleantv.launcher.childlock.ChildLockRule> list = new ArrayList<>();
                    for (int i = 0; i < rulesArr.length(); i++) {
                        JSONObject rObj = rulesArr.getJSONObject(i);
                        com.cleantv.launcher.childlock.ChildLockRule r = new com.cleantv.launcher.childlock.ChildLockRule();
                        r.setId(rObj.optString("id", java.util.UUID.randomUUID().toString()));
                        r.setName(rObj.optString("name", "管控时段"));
                        r.setEnabled(rObj.optBoolean("enabled", true));
                        r.setStartHour(rObj.optInt("startHour", 16));
                        r.setStartMinute(rObj.optInt("startMinute", 0));
                        r.setEndHour(rObj.optInt("endHour", 22));
                        r.setEndMinute(rObj.optInt("endMinute", 0));
                        r.setSingleLimitMinutes(rObj.optInt("singleLimitMinutes", 30));
                        r.setCooldownMinutes(rObj.optInt("cooldownMinutes", 60));
                        r.setDailyLimitMinutes(rObj.optInt("dailyLimitMinutes", 90));
                        JSONArray dArr = rObj.optJSONArray("days");
                        List<Integer> daysList = new ArrayList<>();
                        if (dArr != null) {
                            for (int d = 0; d < dArr.length(); d++) daysList.add(dArr.getInt(d));
                        }
                        r.setDays(daysList);
                        list.add(r);
                    }
                    com.cleantv.launcher.childlock.ChildLockStore.getInstance(mContext).saveRules(list);
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", resp.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    public static String getIpAddress(Context context) {
        try {
            // First check Wi-Fi manager
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo wifiInfo = wm.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                if (ipInt != 0) {
                    return (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
                }
            }

            // Fallback: network interfaces (Ethernet / 有线网卡)
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get local IP", e);
        }
        return "192.168.1.x";
    }
}
