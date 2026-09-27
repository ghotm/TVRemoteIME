package com.android.tvremoteime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;

import com.android.tvremoteime.adb.AdbHelper;

import java.util.List;

import player.XLVideoPlayActivity;
import player.widget.media.IjkVideoView;

/**
 * Helper class for video playback.
 *
 * 两种播放通道：
 * 1) 内置 ijkplayer（XLVideoPlayActivity）：默认通道，支持 m3u8/mp4 直链，遥控器操作体验完整；
 * 2) 系统视频播放器（useSystem=true）：显式指定 TCL 系统播放器组件，并支持「停止播放」。
 *
 * 「停止播放」会先结束内置播放器，再通过媒体键 + 内置 ADB 停止系统播放器进程。
 */
public class VideoPlayHelper {

    /** 播放 http(s) 流时注入的默认 User-Agent（部分源会拒绝无 UA / 非浏览器 UA 的请求）。 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36";

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

    /** 通用 ACTION_VIEW 回退时要排除的「内置播放器」自身 Activity。 */
    private static final String SELF_PLAYER_ACTIVITY = "player.XLVideoPlayActivity";

    /** 当前是否由本应用发起过播放（用于「停止播放」判断）。 */
    private static volatile boolean isPlaying = false;

    /** 最近一次播放的地址与时间（调试用）。 */
    private static volatile String lastPlayUrl = null;
    private static volatile long lastPlayTime = 0;

    /** 兼容旧调用：系统播放器。 */
    public static void playUrl(Context context, String url, int videoIndex, boolean useSystem) {
        playUrl(context, url, videoIndex, useSystem, false, null);
    }

    /**
     * 播放视频。
     *
     * @param context    Android context
     * @param url        Video URL to play
     * @param videoIndex Video index（未用，保留兼容）
     * @param useSystem  true=系统播放器；false=内置 ijkplayer（默认）
     * @param forceVod   强制按「点播」处理（影视仓等 VOD 场景，避免 http 直链被误判为直播）；
     *                   仅对内置 ijkplayer 生效，系统播放器忽略
     * @param title      播放标题（可选，内置播放器标题栏显示）
     */
    public static void playUrl(Context context, String url, int videoIndex, boolean useSystem,
                               boolean forceVod, String title) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (Environment.needDebug) {
            Environment.debug(IMEService.TAG, "Playing video, useSystem=" + useSystem
                    + ", forceVod=" + forceVod + ", url=" + url);
        }

        if (useSystem) {
            playWithSystemPlayer(context, url);
            return;
        }

        // 内置 ijkplayer 通道（默认）
        try {
            IjkVideoView.sUserAgent = DEFAULT_UA;
            IjkVideoView.sLastError = null;
            IjkVideoView.sLastErrorTime = 0;
            XLVideoPlayActivity.intentTo(XLVideoPlayActivity.class, context, url, title,
                    videoIndex, forceVod);
            markPlaying(url);
        } catch (Throwable t) {
            // ijk 启动异常（如 so 加载失败）：回退系统播放器
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "ijk player failed, fallback to system player: "
                        + t.getMessage());
            }
            playWithSystemPlayer(context, url);
        }
    }

    /**
     * 系统播放器通道：优先显式指定候选组件（避免「打开方式」选择框），
     * 通用回退时排除「内置播放器」自身 Activity。
     */
    private static void playWithSystemPlayer(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 1. 优先显式指定系统视频播放器组件
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

            // 2. 回退：查询所有可处理 video/* 的组件，排除内置 ijk 播放器自身
            List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(intent, 0);
            if (resolveInfos != null) {
                for (ResolveInfo ri : resolveInfos) {
                    if (ri == null || ri.activityInfo == null) {
                        continue;
                    }
                    String cls = ri.activityInfo.name;
                    if (SELF_PLAYER_ACTIVITY.equals(cls)) {
                        continue;
                    }
                    Intent explicit = new Intent(intent);
                    explicit.setClassName(ri.activityInfo.packageName, cls);
                    try {
                        context.startActivity(explicit);
                        markPlaying(url);
                        return;
                    } catch (Exception ignore) {
                    }
                }
            }

            // 3. 全部失败：通用启动（可能弹出选择框）
            context.startActivity(intent);
            markPlaying(url);
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "Failed to play video: " + e.getMessage());
            }
        }
    }

    /**
     * 停止当前播放。分三步：
     * 0) 结束内置 ijk 播放器（若正在播放）；
     * 1) 发送媒体停止/暂停键（作用于当前活跃的媒体会话）；
     * 2) 通过内置 ADB 强制停止系统播放器进程（确保停止；只针对播放器包）。
     *
     * @return 之前是否存在由本应用发起的播放（供调用方参考）
     */
    public static boolean stopPlay(final Context context) {
        boolean hadPlaying = isPlaying;
        isPlaying = false;
        lastPlayTime = 0;

        // 0) 结束内置 ijk 播放器
        try {
            XLVideoPlayActivity.stopIfRunning();
        } catch (Throwable ignored) {
        }

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

    /** 最近一次内置播放器错误（framework_err/impl_err）；无错误为 null。-10000 表示 IJK 通用错误。 */
    public static String getLastPlayError() {
        return IjkVideoView.sLastError;
    }

    /** 最近一次内置播放器错误发生时间（毫秒）；无错误为 0。 */
    public static long getLastPlayErrorTime() {
        return IjkVideoView.sLastErrorTime;
    }
}