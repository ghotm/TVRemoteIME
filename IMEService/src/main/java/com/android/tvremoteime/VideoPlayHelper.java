package com.android.tvremoteime;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;

import com.android.tvremoteime.adb.AdbHelper;

/**
 * Helper class for video playback.
 * 使用系统视频播放器播放，并提供「停止播放」能力。
 *
 * 说明：播放本身交给电视上的系统视频播放器（外部应用），
 * 因此「停止播放」通过「媒体停止/暂停键 + 内置 ADB 强制停止播放器进程」来实现。
 */
public class VideoPlayHelper {

    /**
     * 系统视频播放器候选（显式指定播放器组件）：
     * 1) 避免系统每次弹出「打开方式」选择框；
     * 2) 让「停止播放」能精确停止该播放器进程。
     */
    private static final String[][] VIDEO_PLAYER_COMPONENTS = new String[][]{
            {"com.tcl.ui_mediaCenter", "com.tcl.common.mediaplayer.video.UI.VideoPlayerActivity"},
    };

    /**
     * 停止播放时需要强制停止的系统播放器包名。
     * 只放「视频播放器」本身，避免误伤文件管理器等其他应用。
     */
    private static final String[] STOP_PLAYER_PACKAGES = new String[]{
            "com.tcl.ui_mediaCenter",
    };

    /** 当前是否由本应用发起过播放（用于「停止播放」判断）。 */
    private static volatile boolean isPlaying = false;

    /** 最近一次播放的地址与时间（调试用）。 */
    private static volatile String lastPlayUrl = null;
    private static volatile long lastPlayTime = 0;

    /**
     * Play video URL using system player.
     *
     * @param context    Android context
     * @param url        Video URL to play
     * @param videoIndex Video index (unused)
     * @param useSystem  Whether to use system player (always system player in this version)
     */
    public static void playUrl(Context context, String url, int videoIndex, boolean useSystem) {
        if (TextUtils.isEmpty(url)) {
            return;
        }

        if (Environment.needDebug) {
            Environment.debug(IMEService.TAG, "Playing video with system player, url=" + url);
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 1. 优先显式指定系统视频播放器组件（避免弹出「打开方式」选择框）
            for (String[] component : VIDEO_PLAYER_COMPONENTS) {
                Intent explicit = new Intent(intent);
                explicit.setClassName(component[0], component[1]);
                try {
                    if (context.getPackageManager().resolveActivity(explicit, 0) != null) {
                        context.startActivity(explicit);
                        markPlaying(url);
                        return;
                    }
                } catch (Exception ignore) {
                    // 该候选组件不可用，尝试下一个
                }
            }

            // 2. 回退：通用方案，交由系统当前的播放器处理
            context.startActivity(intent);
            markPlaying(url);
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "Failed to play video: " + e.getMessage());
            }
        }
    }

    /**
     * 停止当前播放。
     * 播放由系统视频播放器（外部应用）承担，本方法通过两步停止：
     * 1) 发送媒体停止/暂停键（作用于当前活跃的媒体会话）；
     * 2) 通过内置 ADB 强制停止系统播放器进程（确保停止；只针对播放器包）。
     *
     * @return 之前是否存在由本应用发起的播放（供调用方参考）
     */
    public static boolean stopPlay(final Context context) {
        boolean hadPlaying = isPlaying;
        isPlaying = false;
        lastPlayTime = 0;

        // 1) 媒体键：停止/暂停当前活跃的媒体会话
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                long now = SystemClock.uptimeMillis();
                audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            }
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "Failed to dispatch media key: " + e.getMessage());
            }
        }

        // 2) 内置 ADB：强制停止系统播放器进程（只针对播放器包，不影响其他应用）
        try {
            if (AdbHelper.getInstance() == null) {
                AdbHelper.createInstance();
            }
            if (AdbHelper.initService(context)) {
                AdbHelper adbHelper = AdbHelper.getInstance();
                if (adbHelper != null) {
                    StringBuilder command = new StringBuilder("shell:");
                    for (String pkg : STOP_PLAYER_PACKAGES) {
                        command.append("am force-stop ").append(pkg).append("; ");
                    }
                    adbHelper.sendData(command.toString());
                    if (Environment.needDebug) {
                        Environment.debug(IMEService.TAG, "Sent stop play command: " + command);
                    }
                }
            }
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "Failed to stop player via adb: " + e.getMessage());
            }
        }

        return hadPlaying;
    }

    private static void markPlaying(String url) {
        isPlaying = true;
        lastPlayUrl = url;
        lastPlayTime = SystemClock.uptimeMillis();
    }

    public static boolean isPlaying() {
        return isPlaying;
    }

    public static String getLastPlayUrl() {
        return lastPlayUrl;
    }

    public static long getLastPlayTime() {
        return lastPlayTime;
    }
}
