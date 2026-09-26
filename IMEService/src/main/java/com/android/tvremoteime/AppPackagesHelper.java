package com.android.tvremoteime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
/**
 * Created by kingt on 2018/1/9.
 */

public class AppPackagesHelper {

    public static class AppInfo implements Serializable{
        private String lable;
        private String packageName;
        private String apkPath;
        private boolean isSysApp;

        public String getLable() {
            return lable;
        }

        public void setLable(String lable) {
            this.lable = lable;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public boolean isSysApp() {
            return isSysApp;
        }

        public void setSysApp(boolean sysApp) {
            isSysApp = sysApp;
        }
        public JSONObject toJSONObject()
        {
            JSONObject obj = new JSONObject();
            try {
                obj.put("lable", getLable());
                obj.put("packageName", getPackageName());
                obj.put("apkPath", getApkPath());
                obj.put("isSysApp",  isSysApp());
            }catch (JSONException e) {
                e.printStackTrace();
            }
            return obj;
        }

        public String getApkPath() {
            return apkPath;
        }

        public void setApkPath(String apkPath) {
            this.apkPath = apkPath;
        }
    }

    public static String getCurrentPackageVersion(Context context){
        String version = "1.0.0";
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = packageInfo.versionName;
        }catch (PackageManager.NameNotFoundException e){}
        return version;
    }

    public static List<AppInfo> queryAppInfo(Context context, boolean containSysApp){
        PackageManager pm = context.getPackageManager();
        // 注意：不能用 MATCH_UNINSTALLED_PACKAGES，否则会把「已卸载但保留数据」的应用也列出来（幽灵条目）
        List<ApplicationInfo> listAppcations = pm
                .getInstalledApplications(0);
        List<AppInfo> appInfos = new ArrayList<AppInfo>();
        for (ApplicationInfo app : listAppcations) {
            if(containSysApp || (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                boolean isSysApp = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                //过滤掉系统底层的app
                if(isSysApp &&
                        (app.packageName.startsWith("com.android.") || app.packageName.equals("android")))continue;
                AppInfo appInfo = new AppInfo();
                appInfo.setLable((String) app.loadLabel(pm));
                appInfo.setPackageName(app.packageName);
                appInfo.setApkPath(app.sourceDir);
                appInfo.setSysApp(isSysApp);
                appInfos.add(appInfo);
            }
        }
        Collections.sort(appInfos, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo o1, AppInfo o2) {
                int i1 = (o1.isSysApp ? 2 : 1);
                int i2 = (o2.isSysApp ? 2 : 1);
                if(i1 == i2){
                    return o1.getLable().compareTo(o2.getLable());
                }else{
                    return (i1 < i2) ? -1 : 1;
                }
            }
        });
        return  appInfos;
    }
    public static String getQueryAppInfoJsonString(Context context, boolean containSysApp){
        List<AppInfo> appInfos = queryAppInfo(context, containSysApp);
        JSONArray array = new JSONArray();
        for(AppInfo app : appInfos){
            array.put(app.toJSONObject());
        }
        return  array.toString();
    }

    private static ApplicationInfo getApplicationInfo(String packageName, Context context){
        ApplicationInfo applicationInfo = null;
        if(!packageName.isEmpty()) {
            PackageManager pm = context.getPackageManager();
            try {
                applicationInfo = pm.getApplicationInfo(packageName, 0);
            }catch (PackageManager.NameNotFoundException ex){
                applicationInfo = null;
            }
        }
        return  applicationInfo;
    }

    public static void installPackage(final File apkFile, final Context context){
        try {
            Uri uri = Uri.fromFile(apkFile);
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            context.startActivity(intent);
            Log.i(IMEService.TAG, String.format("已安装应用包[%s]", apkFile.getName()));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("安装应用包[%s]出错", apkFile.getName()), ex);
        }
    }

    public static void uninstallPackage(final String packageName, final Context context){
        if(getApplicationInfo(packageName, context) == null)return;;
        try {
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + packageName));
            context.startActivity(intent);
            Log.i(IMEService.TAG, String.format("已删除应用包[%s]", packageName));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("删除应用包[%s]出错", packageName), ex);
        }
    }

    public static void runPackage(final String packageName, final Context context){
        if(getApplicationInfo(packageName, context) == null)return;;
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if(intent != null){
                context.startActivity(intent);
            }
            Log.i(IMEService.TAG, String.format("已运行应用包[%s]", packageName));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("运行应用包[%s]出错", packageName), ex);
        }
    }
    /**
     * 打开系统设置（多级回退，兼容标准系统与 TCL 等定制固件）
     * 1. 参数为显式包名 → 按包名启动其入口
     * 2. 参数为标准 action → 解析存在才启动（避免 ActivityNotFoundException）
     * 3. 常见系统设置包（com.android.settings / com.tcl.settings 等）逐个尝试启动
     * 4. 全部失败返回 false（调用方提示用户）
     */
    public static boolean runSystemPackage(final String packageName, final Context context){
        try {
            PackageManager pm = context.getPackageManager();
            // 1. 显式包名：直接按其启动入口启动
            if(packageName != null && packageName.contains(".")){
                Intent pkgIntent = new Intent(Intent.ACTION_MAIN);
                pkgIntent.setPackage(packageName);
                pkgIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(pm.resolveActivity(pkgIntent, 0) != null){
                    context.startActivity(pkgIntent);
                    Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", packageName));
                    return true;
                }
            }
            // 2. 标准 action（如 android.settings.SETTINGS）：解析存在才启动
            if(packageName != null && !packageName.contains(".")){
                Intent actionIntent = new Intent(packageName);
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(pm.resolveActivity(actionIntent, 0) != null){
                    context.startActivity(actionIntent);
                    Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", packageName));
                    return true;
                }
            }
            // 3. 常见系统设置包逐个尝试（标准 Android 与 TCL 定制固件）
            String[] settingsPkgs = new String[]{"com.android.settings", "com.tcl.settings"};
            for(String pkg : settingsPkgs){
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                if(launchIntent != null){
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launchIntent);
                    Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", pkg));
                    return true;
                }
            }
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("运行系统应用包[%s]出错", packageName), ex);
        }
        return false;
    }

    public static byte[] getAppIcon(String packageName, Context context){
        ApplicationInfo applicationInfo = getApplicationInfo(packageName, context);
        if(applicationInfo == null) return  null;
        Drawable icon = applicationInfo.loadIcon(context.getPackageManager());
        if(icon == null) return null;
        // Android 8.0+ 自适应图标等并非 BitmapDrawable 子类，不能强制转型；
        // 统一用 Canvas 绘制到 Bitmap（兼容任意 Drawable 实现）
        int width = Math.max(icon.getIntrinsicWidth(), 1);
        int height = Math.max(icon.getIntrinsicHeight(), 1);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        icon.setBounds(0, 0, width, height);
        icon.draw(canvas);

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, data);
        return data.toByteArray();
    }
}
