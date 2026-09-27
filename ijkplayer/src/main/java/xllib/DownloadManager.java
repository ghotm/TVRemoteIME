package xllib;

import android.content.Context;

import com.xunlei.downloadlib.XLDownloadManager;
import com.xunlei.downloadlib.XLTaskHelper;

/**
 * Created by kingt on 2018/2/2.
 */

public class DownloadManager {
    private Context context;

    private DownloadManager(){
        this.downloadTask = new DownloadTask();
    }

    private static volatile DownloadManager instance = null;

    public static DownloadManager instance() {
        if (instance == null) {
            synchronized (DownloadManager.class) {
                if (instance == null) {
                    instance = new DownloadManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context){
        if(this.context == null) {
            try {
                XLTaskHelper.init(context);
            } catch (Throwable ignored) {
                // 迅雷 SDK 初始化失败时忽略：http(s) 直播/直链媒体不依赖迅雷，
                // 由 ijkplayer 直接播放（见 DownloadTask / FileUtils.isLiveMedia）
            }
        }
        this.context = context;
    }

    private DownloadTask downloadTask;
    public DownloadTask taskInstance(){
        return this.downloadTask;
    }
}
