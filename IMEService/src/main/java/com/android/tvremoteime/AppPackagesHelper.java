package com.android.tvremoteime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

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

import com.android.tvremoteime.adb.AdbHelper;
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
            PackageManager pm = context.getPackageManager();
            // 用 FileProvider 生成 content:// URI（Uri.fromFile 在 Android 7+ 会抛 FileUriExposedException）
            Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apkFile);
            String apkMimeType = "application/vnd.android.package-archive";
            int installFlags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION;

            // 1. 优先显式使用系统 PackageInstaller：
            //    部分设备上 APK 的 ACTION_VIEW 默认入口会被第三方应用（如 AppManager）抢占，导致弹出错误界面
            String[][] systemInstallers = new String[][]{
                    {"com.android.packageinstaller", "com.android.packageinstaller.InstallStart"},
                    {"com.google.android.packageinstaller", "com.google.android.packageinstaller.InstallStart"}
            };
            for (String[] installer : systemInstallers) {
                Intent explicit = new Intent();
                explicit.addFlags(installFlags);
                explicit.setAction(Intent.ACTION_VIEW);
                explicit.setDataAndType(uri, apkMimeType);
                explicit.setClassName(installer[0], installer[1]);
                try {
                    if (pm.resolveActivity(explicit, 0) != null) {
                        context.startActivity(explicit);
                        Log.i(IMEService.TAG, String.format("已安装应用包[%s]", apkFile.getName()));
                        return;
                    }
                } catch (Exception ignore) {
                    // 该候选安装器不可用，尝试下一个
                }
            }
            // 2. 模糊定位：查询能处理 APK 安装意图的 Activity
            Intent viewIntent = new Intent();
            viewIntent.addFlags(installFlags);
            viewIntent.setAction(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, apkMimeType);
            List<ResolveInfo> infos = pm.queryIntentActivities(viewIntent, 0);
            // 2.1 优先选择系统安装器（包名含 packageinstaller 或类名含 installstart），跳过第三方拦截器
            for (ResolveInfo info : infos) {
                if (info.activityInfo == null) continue;
                String pkgName = info.activityInfo.packageName == null ? "" : info.activityInfo.packageName.toLowerCase();
                String clsName = info.activityInfo.name == null ? "" : info.activityInfo.name.toLowerCase();
                if (!pkgName.contains("packageinstaller") && !clsName.contains("installstart")) continue;
                Intent explicit = new Intent(viewIntent);
                explicit.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                try {
                    context.startActivity(explicit);
                    Log.i(IMEService.TAG, String.format("已安装应用包[%s]", apkFile.getName()));
                    return;
                } catch (Exception ignore) {
                    // 尝试下一个
                }
            }
            // 2.2 退而求其次：任意能处理该意图的组件
            for (ResolveInfo info : infos) {
                if (info.activityInfo == null) continue;
                Intent explicit = new Intent(viewIntent);
                explicit.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                try {
                    context.startActivity(explicit);
                    Log.i(IMEService.TAG, String.format("已安装应用包[%s]", apkFile.getName()));
                    return;
                } catch (Exception ignore) {
                    // 尝试下一个
                }
            }
            // 3. 系统默认解析（可能被第三方安装器接管）
            if (pm.resolveActivity(viewIntent, 0) != null) {
                context.startActivity(viewIntent);
                Log.i(IMEService.TAG, String.format("已安装应用包[%s]", apkFile.getName()));
                return;
            }
            Log.e(IMEService.TAG, String.format("安装应用包[%s]出错：未找到可用的系统安装器", apkFile.getName()));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("安装应用包[%s]出错", apkFile.getName()), ex);
        }
    }

    /**
     * 卸载应用（与安装一致：优先直接调用系统卸载界面）
     * 1. 显式启动 PackageInstaller 的卸载页面（部分定制固件不响应隐式 ACTION_DELETE）
     * 2. 隐式 ACTION_DELETE（系统原生卸载页）
     * 3. 传统卸载页：android.intent.action.UNINSTALL_PACKAGE
     * 4. 通过内置 ADB 客户端执行 pm uninstall（需电视已开启网络调试）
     */
    public static void uninstallPackage(final String packageName, final Context context){
        if(getApplicationInfo(packageName, context) == null)return;
        try {
            PackageManager pm = context.getPackageManager();
            Uri packageUri = Uri.parse("package:" + packageName);
            // 1. 显式启动系统卸载界面（与安装使用 InstallStart 同理，兼容不响应隐式意图的定制固件）
            String[][] uninstallers = new String[][]{
                    {"com.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"},
                    {"com.google.android.packageinstaller", "com.google.android.packageinstaller.UninstallerActivity"}
            };
            for(String[] uninstaller : uninstallers){
                Intent explicit = new Intent();
                explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                explicit.setAction(Intent.ACTION_DELETE);
                explicit.setData(packageUri);
                explicit.setClassName(uninstaller[0], uninstaller[1]);
                if(pm.resolveActivity(explicit, 0) != null){
                    try {
                        context.startActivity(explicit);
                        Log.i(IMEService.TAG, String.format("已删除应用包[%s]（系统卸载界面）", packageName));
                        return;
                    } catch (Exception ignore) { }
                }
            }
            // 2. 隐式系统原生卸载入口
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Intent.ACTION_DELETE);
            intent.setData(packageUri);
            if (pm.resolveActivity(intent, 0) != null) {
                context.startActivity(intent);
                Log.i(IMEService.TAG, String.format("已删除应用包[%s]", packageName));
                return;
            }
            // 3. 传统卸载入口（部分固件用 UNINSTALL_PACKAGE）
            Intent uninstallIntent = new Intent();
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            uninstallIntent.setAction("android.intent.action.UNINSTALL_PACKAGE");
            uninstallIntent.setData(packageUri);
            if (pm.resolveActivity(uninstallIntent, 0) != null) {
                context.startActivity(uninstallIntent);
                Log.i(IMEService.TAG, String.format("已删除应用包[%s]", packageName));
                return;
            }
            // 4. 回退：通过内置 ADB 客户端卸载（适用于系统未提供卸载入口的定制电视）
            if (uninstallPackageByAdb(packageName, context)) {
                Log.i(IMEService.TAG, String.format("已通过 ADB 卸载应用包[%s]", packageName));
                return;
            }
            Log.e(IMEService.TAG, String.format("删除应用包[%s]出错：系统未提供卸载入口，且 ADB 不可用", packageName));
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("删除应用包[%s]出错", packageName), ex);
        }
    }

    /**
     * 通过内置 ADB 客户端（连接电视自身 adb 服务）执行 pm uninstall
     */
    private static boolean uninstallPackageByAdb(final String packageName, final Context context){
        try {
            if(AdbHelper.getInstance() == null){
                AdbHelper.createInstance();
            }
            AdbHelper.initService(context);
            AdbHelper helper = AdbHelper.getInstance();
            if(helper == null){
                return false;
            }
            helper.sendData("shell:pm uninstall --user 0 " + packageName);
            return true;
        }catch (Exception ex){
            Log.e(IMEService.TAG, String.format("通过 ADB 卸载应用包[%s]出错", packageName), ex);
            return false;
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
            // 1. 通用方案：参数含点优先按包名、再按 action 尝试（resolveActivity 通过才启动）
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
                Intent actionIntent = new Intent(packageName);
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(pm.resolveActivity(actionIntent, 0) != null){
                    context.startActivity(actionIntent);
                    Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", packageName));
                    return true;
                }
            }
            // 2. 已知设置包优先（覆盖主流系统与常见电视品牌，比动态扫描更可靠）
            String[] settingsPkgs = new String[]{"com.android.settings", "com.tcl.settings",
                    "com.hisense.settings", "com.skyworth.settings", "com.letv.settings",
                    "com.miui.settings", "com.huawei.android.settings"};
            for(String pkg : settingsPkgs){
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                if(launchIntent != null){
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launchIntent);
                    Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", pkg));
                    return true;
                }
            }
            // 3. 模糊定位兜底：动态扫描系统应用，仅精确匹配「设置」类，避免误命中第三方应用
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(context.getPackageName())) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue; // 仅系统应用
                String label = null;
                try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Exception ignore) {}
                String pkgLower = pkg.toLowerCase();
                boolean isSettings = pkgLower.endsWith(".settings")
                        || (label != null && ("设置".equals(label.trim()) || "Settings".equalsIgnoreCase(label.trim())));
                if (isSettings) {
                    Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            context.startActivity(launchIntent);
                            Log.i(IMEService.TAG, String.format("已运行系统应用包[%s]", pkg));
                            return true;
                        } catch (Exception ignore) { }
                    }
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
