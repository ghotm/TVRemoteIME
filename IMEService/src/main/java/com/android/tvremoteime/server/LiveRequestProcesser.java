package com.android.tvremoteime.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.android.tvremoteime.http.HttpFetcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * Web 端直播源更新：从 awesome-zhuiju-free 的 tvbox_config 配置中提取 `lives[]`，
 * 用户选择后下载直播列表（M3U / TVBox txt），转换为本项目 tv.txt 格式并整体替换（自动备份）。
 *
 * 端点：
 * - GET  /live/sources[?refresh=1]   直播源清单（后台刷新 + 缓存）
 * - GET  /live/backups               备份列表（最多 3 份，按时间倒序）
 * - POST /live/apply {url, name}     下载并应用某个直播列表
 * - POST /live/restore {file}        还原到指定备份
 *
 * tv.txt 的读写与 TVRequestProcesser 共用 RemoteServerFileManager.tvFileLock。
 */
public class LiveRequestProcesser implements RequestProcesser {

    private static final String TAG = "LiveRequestProcesser";

    private static final String RAW_RESOURCES_URL =
            "https://raw.githubusercontent.com/laoma2053/awesome-zhuiju-free/main/resources/resources.json";
    private static final String CDN_RESOURCES_URL =
            "https://cdn.jsdelivr.net/gh/laoma2053/awesome-zhuiju-free@main/resources/resources.json";
    private static final String RESOURCES_CATEGORY_TVBOX = "tvbox_config";

    private static final int CONFIG_TIMEOUT_MS = 6000;
    private static final int APPLY_CONNECT_TIMEOUT_MS = 10000;
    private static final int APPLY_READ_TIMEOUT_MS = 15000;
    private static final int MAX_BACKUPS = 3;

    /** 备份文件名：tv.txt.bak.<毫秒时间戳>（严格全匹配，防路径穿越）。 */
    private static final Pattern BACKUP_NAME = Pattern.compile("tv\\.txt\\.bak\\.\\d+");
    private static final String BACKUP_PREFIX = "tv.txt.bak.";

    private final Context context;
    private final File tvFile = new File(RemoteServerFileManager.baseDir, "tv.txt");
    private final File sourcesCacheFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public LiveRequestProcesser(Context context) {
        this.context = context;
        this.sourcesCacheFile = new File(context.getFilesDir(), "live_sources.json");
    }

    // ---------------------------------------------------------------- 路由

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (fileName == null) {
            return false;
        }
        switch (fileName) {
            case "/live/sources":
            case "/live/backups":
            case "/live/apply":
            case "/live/restore":
                return true;
            default:
                return false;
        }
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        try {
            switch (fileName) {
                case "/live/sources":
                    if (session.getMethod() == NanoHTTPD.Method.GET) {
                        return jsonResponse(handleSources("1".equals(params.get("refresh"))));
                    }
                    break;
                case "/live/backups":
                    if (session.getMethod() == NanoHTTPD.Method.GET) {
                        return jsonResponse(handleBackups());
                    }
                    break;
                case "/live/apply":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        return jsonResponse(handleApply(params.get("url"), params.get("name")));
                    }
                    break;
                case "/live/restore":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        return jsonResponse(handleRestore(params.get("file")));
                    }
                    break;
                default:
                    break;
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        } catch (Exception e) {
            Log.e(TAG, "处理请求出错: " + fileName, e);
            return error("服务器内部错误: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 源清单

    private String handleSources(boolean force) throws JSONException {
        JSONObject cache = loadSourcesCache();
        JSONArray list = cache != null ? cache.optJSONArray("list") : null;
        boolean cacheEmpty = list == null || list.length() == 0;
        if (force || cacheEmpty) {
            triggerRefresh();
        }
        JSONObject result = new JSONObject();
        result.put("code", 0);
        result.put("refreshing", refreshing.get());
        result.put("lastUpdated", cache != null ? cache.optLong("lastUpdated", 0) : 0);
        result.put("list", list != null ? list : new JSONArray());
        return result.toString();
    }

    private void triggerRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshSourcesSync();
                } catch (Exception e) {
                    Log.e(TAG, "刷新直播源清单失败", e);
                } finally {
                    refreshing.set(false);
                }
            }
        });
    }

    private void refreshSourcesSync() {
        JSONArray list = new JSONArray();
        JSONArray resources = null;
        try {
            String body = fetchResourcesJson();
            resources = new JSONObject(body).optJSONArray("resources");
        } catch (Exception e) {
            Log.e(TAG, "拉取 resources.json 失败", e);
        }
        if (resources == null) {
            resources = new JSONArray();
        }
        for (int i = 0; i < resources.length(); i++) {
            JSONObject res = resources.optJSONObject(i);
            if (res == null || !RESOURCES_CATEGORY_TVBOX.equals(res.optString("category"))) {
                continue;
            }
            String configName = res.optString("name", "配置");
            String configUrl = res.optString("url", "");
            if (TextUtils.isEmpty(configUrl)) {
                continue;
            }
            try {
                HttpFetcher.Fetched fetched = HttpFetcher.fetchBytes(configUrl, null, CONFIG_TIMEOUT_MS, CONFIG_TIMEOUT_MS);
                String body = LiveListConverter.decode(fetched.data, fetched.charset);
                JSONObject cfg = new JSONObject(body);
                JSONArray lives = cfg.optJSONArray("lives");
                if (lives == null) {
                    continue;
                }
                for (int j = 0; j < lives.length(); j++) {
                    JSONObject live = lives.optJSONObject(j);
                    if (live == null) {
                        continue;
                    }
                    String name = live.optString("name", "直播源");
                    String url = resolveUrl(configUrl, live.optString("url", ""));
                    if (TextUtils.isEmpty(url)) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("name", name);
                    item.put("configName", configName);
                    item.put("url", url);
                    item.put("error", "");
                    list.put(item);
                }
            } catch (Exception e) {
                JSONObject item = new JSONObject();
                try {
                    item.put("name", "");
                    item.put("configName", configName);
                    item.put("url", "");
                    item.put("error", shorten(e.getMessage()));
                    list.put(item);
                } catch (JSONException je) {
                    Log.w(TAG, "构造失败源条目出错: " + configName, je);
                }
            }
        }
        saveSourcesCache(list, System.currentTimeMillis());
    }

    private String fetchResourcesJson() throws IOException {
        try {
            return HttpFetcher.fetch(RAW_RESOURCES_URL, null, CONFIG_TIMEOUT_MS, CONFIG_TIMEOUT_MS);
        } catch (IOException e) {
            Log.w(TAG, "GitHub raw 拉取 resources.json 失败，改用 CDN", e);
            return HttpFetcher.fetch(CDN_RESOURCES_URL, null, CONFIG_TIMEOUT_MS, CONFIG_TIMEOUT_MS);
        }
    }

    /**
     * 解析相对地址：TVBox 配置中 lives[].url 可能是相对配置文件地址的路径
     * （如 ./lib/tv/ipv6.m3u、/live/xx.m3u、//cdn.xx/x.m3u）。以配置文件的 URL 为基准拼接为绝对地址。
     * 采用手写拼接而非 java.net.URI，避免配置 URL 含中文域名时 URI.create 抛异常。
     */
    private static String resolveUrl(String base, String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        if (TextUtils.isEmpty(base)) {
            return url;
        }
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        // 协议相对地址 //host/path
        if (url.startsWith("//")) {
            return base.substring(0, schemeEnd + 1) + url;
        }
        int pathStart = base.indexOf('/', schemeEnd + 3);
        String origin = pathStart < 0 ? base : base.substring(0, pathStart);
        String basePath = pathStart < 0 ? "/" : base.substring(pathStart);
        // 根相对地址 /path
        if (url.startsWith("/")) {
            return origin + url;
        }
        // 普通相对地址：取配置文件的目录作为基准
        int lastSlash = basePath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? basePath.substring(0, lastSlash + 1) : "/";
        return origin + normalizePath(dir + url);
    }

    /** 规范化路径中的 "." 与 ".." 段。 */
    private static String normalizePath(String path) {
        String[] parts = path.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : stack) {
            sb.append('/').append(p);
        }
        if (path.endsWith("/")) {
            sb.append('/');
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private JSONObject loadSourcesCache() {
        if (!sourcesCacheFile.exists()) {
            return null;
        }
        try {
            return new JSONObject(readFileAsString(sourcesCacheFile));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveSourcesCache(JSONArray list, long timestamp) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("lastUpdated", timestamp);
            o.put("list", list);
            File tmp = new File(sourcesCacheFile.getParentFile(), sourcesCacheFile.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            if (sourcesCacheFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                sourcesCacheFile.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(sourcesCacheFile);
        } catch (Exception e) {
            Log.e(TAG, "保存直播源缓存失败", e);
        }
    }

    // ------------------------------------------------------------ 备份列表

    private String handleBackups() throws JSONException {
        synchronized (RemoteServerFileManager.tvFileLock) {
            JSONObject o = new JSONObject();
            o.put("code", 0);
            o.put("list", listBackups());
            return o.toString();
        }
    }

    private JSONArray listBackups() throws JSONException {
        List<File> files = new ArrayList<>();
        File[] all = RemoteServerFileManager.baseDir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && BACKUP_NAME.matcher(f.getName()).matches()) {
                    files.add(f);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(parseTs(b.getName()), parseTs(a.getName())); // 时间倒序
            }
        });
        JSONArray arr = new JSONArray();
        for (File f : files) {
            JSONObject o = new JSONObject();
            o.put("file", f.getName());
            o.put("time", parseTs(f.getName()));
            o.put("size", f.length());
            arr.put(o);
        }
        return arr;
    }

    private long parseTs(String name) {
        try {
            return Long.parseLong(name.substring(BACKUP_PREFIX.length()));
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 应用/还原

    private String handleApply(String url, String name) throws JSONException {
        if (TextUtils.isEmpty(url)) {
            return errorJson("缺少直播列表地址");
        }
        String lowerUrl = url.toLowerCase();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return errorJson("该源地址为相对路径，请先点「刷新」重新获取源列表");
        }
        LiveListConverter.Result conv;
        try {
            HttpFetcher.Fetched fetched = HttpFetcher.fetchBytes(url, null, APPLY_CONNECT_TIMEOUT_MS, APPLY_READ_TIMEOUT_MS);
            conv = LiveListConverter.convert(fetched.data, fetched.charset);
        } catch (Exception e) {
            Log.e(TAG, "下载直播列表失败: " + url, e);
            return errorJson("下载直播列表失败：" + shorten(e.getMessage()));
        }
        if (conv.sourceCount == 0) {
            return errorJson("未解析到任何直播源（可能格式不支持或列表为空）");
        }
        synchronized (RemoteServerFileManager.tvFileLock) {
            try {
                String backup = backupCurrent();
                writeAtomic(tvFile, conv.text);
                cleanupBackups();
                JSONObject o = new JSONObject();
                o.put("code", "ok");
                o.put("msg", "已更新直播源");
                o.put("channelCount", conv.channelCount);
                o.put("sourceCount", conv.sourceCount);
                o.put("format", conv.format);
                o.put("backup", backup == null ? "" : backup);
                return o.toString();
            } catch (Exception e) {
                Log.e(TAG, "写入直播源失败", e);
                return errorJson("写入直播源失败：" + e.getMessage());
            }
        }
    }

    private String handleRestore(String file) throws JSONException {
        if (TextUtils.isEmpty(file)
                || !BACKUP_NAME.matcher(file).matches()
                || file.contains("/") || file.contains("\\") || file.contains("..")) {
            return errorJson("备份文件名非法");
        }
        synchronized (RemoteServerFileManager.tvFileLock) {
            File bak = new File(RemoteServerFileManager.baseDir, file);
            if (!bak.exists()) {
                return errorJson("备份不存在");
            }
            try {
                // 还原前把当前内容也备份一份，使「还原」本身可逆
                backupCurrent();
                copyAtomic(bak, tvFile);
                cleanupBackups();
                JSONObject o = new JSONObject();
                o.put("code", "ok");
                o.put("msg", "已还原到备份");
                o.put("time", parseTs(file));
                return o.toString();
            } catch (Exception e) {
                Log.e(TAG, "还原直播源失败", e);
                return errorJson("还原失败：" + e.getMessage());
            }
        }
    }

    /** 返回 code=error 的 JSON 字符串（用于需要返回 String 的内部方法）。 */
    private static String errorJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("code", "error");
            o.put("msg", msg == null ? "" : msg);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** 把当前 tv.txt 备份为 tv.txt.bak.<毫秒时间戳>，返回备份文件名（无源文件返回 null）。 */
    private String backupCurrent() throws IOException {
        if (!tvFile.exists()) {
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(new Date());
        File bak = new File(RemoteServerFileManager.baseDir, BACKUP_PREFIX + stamp);
        copyFile(tvFile, bak);
        return bak.getName();
    }

    private void cleanupBackups() {
        JSONArray arr;
        try {
            arr = listBackups();
        } catch (JSONException e) {
            return;
        }
        for (int i = MAX_BACKUPS; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String f = o.optString("file", "");
            if (TextUtils.isEmpty(f) || !BACKUP_NAME.matcher(f).matches()) {
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            new File(RemoteServerFileManager.baseDir, f).delete();
        }
    }

    // ------------------------------------------------------------ 文件工具

    private void writeAtomic(File target, String text) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖目标文件: " + target.getName());
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("重命名临时文件失败");
        }
    }

    private void copyAtomic(File src, File target) throws IOException {
        writeAtomic(target, readFileAsString(src));
    }

    private void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String readFileAsString(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private NanoHTTPD.Response jsonResponse(String text) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8", text);
    }

    private NanoHTTPD.Response error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("code", "error");
            o.put("msg", msg);
        } catch (JSONException ignored) {
        }
        return jsonResponse(o.toString());
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
