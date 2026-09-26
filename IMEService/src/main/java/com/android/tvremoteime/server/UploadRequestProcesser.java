package com.android.tvremoteime.server;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.AppPackagesHelper;
import com.android.tvremoteime.VideoPlayHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import com.android.tvremoteime.util.FileUtils;

/**
 * Created by kingt on 2018/1/7.
 */

public class UploadRequestProcesser implements RequestProcesser {
    private Context context;

    public UploadRequestProcesser(Context context){
        this.context = context;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.POST && "/upload".equalsIgnoreCase(fileName);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        String uploadFileName  = params.get("file");
        Boolean autoInstall = "true".equalsIgnoreCase(params.get("autoInstall"));
        String localFilename = files.get("file");
        if(!TextUtils.isEmpty(uploadFileName)) {
            if (!TextUtils.isEmpty(localFilename)) {
                // 将上传的临时文件移动到公共下载目录，避免落在应用私有目录导致用户在其他文件管理器中找不到
                localFilename = moveToDownloadDir(localFilename, uploadFileName);
                if(autoInstall) {
                    if (localFilename.endsWith(".apk")) {
                        //执行安装
                        AppPackagesHelper.installPackage(new File(localFilename), this.context);
                    }
                    else if (FileUtils.isMediaFile(localFilename)){
                        //执行播放
                        VideoPlayHelper.playUrl(this.context, localFilename, 0, "true".equalsIgnoreCase(params.get("useSystem")));
                    }
                }
            }
        }
        if(TextUtils.isEmpty(localFilename)){
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,  "{\"success\":false}");
        }else{
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK,  String.format("{\"success\":true, \"filePath\":\"%s\"}", localFilename.replaceAll("\\\\", "\\\\")));
        }
    }

    /**
     * 将上传文件移动到公共下载目录 Download/TVRemoteIME 下，便于用户在其他文件管理器/电视文件中查看。
     * 移动失败（如未授予「所有文件访问权限」）时保留原临时路径，不影响后续安装/播放。
     */
    private String moveToDownloadDir(String localFilename, String uploadFileName) {
        try {
            File src = new File(localFilename);
            if (!src.exists()) return localFilename;
            File targetDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "TVRemoteIME");
            if (!targetDir.exists() && !targetDir.mkdirs()) return localFilename;
            String fileName = TextUtils.isEmpty(uploadFileName) ? src.getName() : uploadFileName;
            File dest = new File(targetDir, fileName);
            if (dest.exists()) {
                dest = new File(targetDir, System.currentTimeMillis() + "_" + fileName);
            }
            if (src.renameTo(dest)) {
                return dest.getAbsolutePath();
            }
            // 跨挂载点 rename 失败时回退为流式复制
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
                src.delete();
                return dest.getAbsolutePath();
            } catch (Exception e2) {
                Log.e("UploadRequestProcesser", "复制上传文件到公共目录失败", e2);
                return localFilename;
            }
        } catch (Exception e) {
            Log.e("UploadRequestProcesser", "移动上传文件到公共目录失败", e);
            return localFilename;
        }
    }
}
