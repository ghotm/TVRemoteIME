package com.android.tvremoteime.server;

import android.content.Context;
import android.text.TextUtils;

import com.android.tvremoteime.Environment;
import com.android.tvremoteime.IMEService;
import com.android.tvremoteime.VideoPlayHelper;
import com.android.tvremoteime.http.HttpFetcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

/**
 * Web 端影视仓功能（聚合直连型苹果CMS 影视站）。
 *
 * 端点：
 * - GET  /movie/sources          源列表（含启停状态、可达性、lastProbeError）
 * - GET  /movie/search?wd=       启用源并发聚合搜索
 * - GET  /movie/detail?source=&id= 详情（含线路分组/选集）
 * - POST /movie/updateSources    拉取 awesome-zhuiju-free → 扫描 tvbox 配置 → 更新源清单
 * - POST /movie/toggleSource     启停单个源 {key, enable}
 * - GET  /movie/playState        当前播放状态
 *
 * 源清单持久化在 getFilesDir()/movie_sources.json，原子写（临时文件 + rename）。
 */
public class MovieRequestProcesser implements RequestProcesser {

    private static final String TAG = "MovieRequestProcesser";

    /** awesome-zhuiju-free 资源清单（在线抓取，备用 CDN）。 */
    private static final String RAW_RESOURCES_URL =
            "https://raw.githubusercontent.com/laoma2053/awesome-zhuiju-free/main/resources/resources.json";
    private static final String CDN_RESOURCES_URL =
            "https://cdn.jsdelivr.net/gh/laoma2053/awesome-zhuiju-free@main/resources/resources.json";
    private static final String RESOURCES_CATEGORY_TVBOX = "tvbox_config";

    private static final int SEARCH_CONCURRENCY_MAX = 8;
    private static final long SEARCH_TIMEOUT_SECONDS = 8;
    private static final long UPDATE_INTERVAL_MS = 24L * 3600 * 1000; // 每天自动更新间隔

    /** 内置兜底源（本机实测可达）。 */
    private static final String[][] BUILTIN_SOURCES = new String[][]{
            {"hhzy", "豪华资源", "https://hhzyapi.com/api.php/provide/vod/"},
            {"ikun", "爱坤资源", "https://ikunzyapi.com/api.php/provide/vod/"},
    };

    private final Context context;
    private final File sourcesFile;
    /** 共享后台线程池（源更新、探测、自动刷新）。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    /** updateSources 互斥，防止手动/自动同时触发。 */
    private final AtomicBoolean updating = new AtomicBoolean(false);

    public MovieRequestProcesser(Context context) {
        this.context = context;
        this.sourcesFile = new File(context.getFilesDir(), "movie_sources.json");
        ensureSeeded();
        maybeAutoUpdateOnStart();
    }

    // ---------------------------------------------------------------- 路由

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && fileName.startsWith("/movie/");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                         Map<String, String> params, Map<String, String> files) {
        try {
            switch (fileName) {
                case "/movie/sources":
                    return jsonResponse(buildSourcesResponse());
                case "/movie/search":
                    return doSearch(params.get("wd"));
                case "/movie/detail":
                    return doDetail(params.get("source"), params.get("id"));
                case "/movie/updateSources":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        triggerUpdate();
                        return jsonResponse(message("ok", "后台更新已启动"));
                    }
                    break;
                case "/movie/toggleSource":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        return doToggleSource(params.get("key"), params.get("enable"));
                    }
                    break;
                case "/movie/playState":
                    if (session.getMethod() == NanoHTTPD.Method.GET) {
                        JSONObject o = new JSONObject();
                        o.put("isPlaying", VideoPlayHelper.isPlaying());
                        o.put("lastPlayUrl", VideoPlayHelper.getLastPlayUrl() != null
                                ? VideoPlayHelper.getLastPlayUrl() : "");
                        return jsonResponse(o);
                    }
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "movie json error: " + e.getMessage());
            }
            return error("json_error");
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "movie error: " + e.getMessage());
            }
            return error("server_error");
        }
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                "Error 404, file not found.");
    }

    // ---------------------------------------------------------------- 源管理

    /** 首次使用写入内置源。 */
    private synchronized void ensureSeeded() {
        if (sourcesFile.exists()) {
            return;
        }
        JSONObject data = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (String[] b : BUILTIN_SOURCES) {
                JSONObject s = new JSONObject();
                s.put("key", b[0]);
                s.put("name", b[1]);
                s.put("api", b[2]);
                s.put("enable", true);
                s.put("status", "recommended");
                s.put("lastProbeError", "");
                s.put("builtin", true);
                s.put("createdAt", System.currentTimeMillis());
                arr.put(s);
            }
            data.put("version", 1);
            data.put("lastUpdated", 0L);
            data.put("sources", arr);
            saveSources(data);
        } catch (JSONException ignored) {
        }
    }

    private synchronized JSONObject loadSources() {
        if (!sourcesFile.exists()) {
            ensureSeeded();
        }
        try {
            FileInputStream fis = new FileInputStream(sourcesFile);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            fis.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            if (!TextUtils.isEmpty(text)) {
                return new JSONObject(text);
            }
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    /** 原子写：先写临时文件再 rename。 */
    private synchronized boolean saveSources(JSONObject data) {
        File tmp = new File(sourcesFile.getAbsolutePath() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(data.toString().getBytes("UTF-8"));
            fos.close();
            if (!tmp.renameTo(sourcesFile)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "save movie_sources failed: " + e.getMessage());
            }
            return false;
        }
    }

    private JSONArray getSourcesArray(JSONObject data) {
        JSONArray arr = data.optJSONArray("sources");
        return arr != null ? arr : new JSONArray();
    }

    private List<Source> getEnabledSources() {
        List<Source> list = new ArrayList<>();
        JSONArray arr = getSourcesArray(loadSources());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if ("false".equalsIgnoreCase(o.optString("enable", "true"))) {
                continue;
            }
            String api = o.optString("api", "");
            if (TextUtils.isEmpty(api)) {
                continue;
            }
            list.add(new Source(o.optString("key"), o.optString("name"), api));
        }
        return list;
    }

    private Source findSource(String key) {
        JSONArray arr = getSourcesArray(loadSources());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && key != null && key.equals(o.optString("key"))) {
                return new Source(o.optString("key"), o.optString("name"), o.optString("api"));
            }
        }
        return null;
    }

    private JSONObject buildSourcesResponse() throws JSONException {
        JSONObject resp = new JSONObject();
        resp.put("code", 0);
        JSONObject data = loadSources();
        JSONArray out = new JSONArray();
        JSONArray arr = getSourcesArray(data);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("key", s.optString("key"));
            item.put("name", s.optString("name"));
            item.put("api", s.optString("api"));
            item.put("enable", "false".equalsIgnoreCase(s.optString("enable", "true")) ? false : true);
            item.put("status", s.optString("status", "pending"));
            item.put("lastProbeError", s.optString("lastProbeError", ""));
            item.put("builtin", s.optBoolean("builtin", false));
            out.put(item);
        }
        resp.put("list", out);
        resp.put("lastUpdated", data.optLong("lastUpdated", 0L));
        return resp;
    }

    private NanoHTTPD.Response doToggleSource(String key, String enable) {
        boolean wantEnable = !"false".equalsIgnoreCase(enable);
        synchronized (this) {
            JSONObject data = loadSources();
            JSONArray arr = getSourcesArray(data);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && key != null && key.equals(o.optString("key"))) {
                    try {
                        o.put("enable", wantEnable);
                    } catch (JSONException ignored) {
                    }
                }
            }
            saveSources(data);
        }
        try {
            return jsonResponse(message("ok", "已更新"));
        } catch (JSONException e) {
            return error("json_error");
        }
    }

    // ---------------------------------------------------------------- 搜索 / 详情

    private NanoHTTPD.Response doSearch(String wd) {
        if (TextUtils.isEmpty(wd)) {
            return error("缺少关键词 wd");
        }
        try {
            wd = wd.trim();
        } catch (Exception ignored) {
        }
        List<Source> sources = getEnabledSources();
        if (sources.isEmpty()) {
            try {
                return jsonResponse(message("ok", "暂无可用的源"));
            } catch (JSONException e) {
                return error("json_error");
            }
        }

        int n = Math.min(sources.size(), SEARCH_CONCURRENCY_MAX);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<JSONArray>> futures = new ArrayList<>();
        Map<Future<JSONArray>, String> futureSource = new HashMap<>();
        for (int i = 0; i < n; i++) {
            final Source s = sources.get(i);
            Future<JSONArray> f = pool.submit(() -> querySource(s, wd));
            futures.add(f);
            futureSource.put(f, s.key);
        }

        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int j = 0; j < futures.size(); j++) {
            Future<JSONArray> f = futures.get(j);
            JSONArray arr;
            try {
                arr = f.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                f.cancel(true);
                arr = null; // 单源超时/失败：跳过，不影响整体
            }
            if (arr == null) {
                continue;
            }
            for (int k = 0; k < arr.length(); k++) {
                try {
                    JSONObject item = arr.optJSONObject(k);
                    if (item == null) {
                        continue;
                    }
                    String name = item.optString("name", "");
                    String year = item.optString("year", "");
                    if (TextUtils.isEmpty(name)) {
                        continue;
                    }
                    String dedup = name + "|" + year;
                    if (seen.contains(dedup)) {
                        continue;
                    }
                    seen.add(dedup);
                    merged.put(item);
                } catch (Exception ignored) {
                }
            }
        }
        pool.shutdownNow();

        try {
            JSONObject resp = new JSONObject();
            resp.put("code", 0);
            resp.put("total", merged.length());
            resp.put("list", merged);
            return jsonResponse(resp);
        } catch (JSONException e) {
            return error("json_error");
        }
    }

    /** 单源搜索：{api}?ac=detail&wd=xx，返回规范化结果数组。 */
    private JSONArray querySource(Source s, String wd) {
        try {
            String api = s.api;
            String sep = api.contains("?") ? "&" : "?";
            String url = api + sep + "ac=detail&wd=" + URLEncoder.encode(wd, "UTF-8");
            String body = HttpFetcher.fetch(url, null);
            if (TextUtils.isEmpty(body)) {
                return null;
            }
            JSONObject root = new JSONObject(body);
            Object rawList = root.opt("list");
            JSONArray list = rawList instanceof JSONArray
                    ? (JSONArray) rawList
                    : (rawList instanceof JSONObject ? new JSONArray().put((JSONObject) rawList) : null);
            if (list == null) {
                return null;
            }
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("source", s.key);
                item.put("sourceName", s.name);
                item.put("id", v.optString("vod_id", ""));
                item.put("name", v.optString("vod_name", ""));
                item.put("year", v.optString("vod_year", ""));
                item.put("sub", v.optString("vod_sub", ""));
                item.put("pic", v.optString("vod_pic", ""));
                item.put("actor", v.optString("vod_actor", ""));
                item.put("director", v.optString("vod_director", ""));
                item.put("blurb", v.optString("vod_blurb", ""));
                if (!TextUtils.isEmpty(item.optString("name"))) {
                    out.put(item);
                }
            }
            return out;
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "search failed source=" + s.key + ": " + e.getMessage());
            }
            return null;
        }
    }

    private NanoHTTPD.Response doDetail(String sourceKey, String id) {
        if (TextUtils.isEmpty(id)) {
            return error("缺少 id");
        }
        Source s = findSource(sourceKey);
        if (s == null) {
            return error("源不存在: " + sourceKey);
        }
        try {
            String sep = s.api.contains("?") ? "&" : "?";
            String url = s.api + sep + "ac=detail&ids=" + URLEncoder.encode(id, "UTF-8");
            String body = HttpFetcher.fetch(url, null);
            if (TextUtils.isEmpty(body)) {
                return error("详情抓取失败");
            }
            JSONObject root = new JSONObject(body);
            Object rawList = root.opt("list");
            JSONArray list = rawList instanceof JSONArray
                    ? (JSONArray) rawList
                    : (rawList instanceof JSONObject ? new JSONArray().put((JSONObject) rawList) : null);
            JSONObject v = list != null ? list.optJSONObject(0) : null;
            if (v == null) {
                return error("未找到该影片");
            }

            JSONObject detail = new JSONObject();
            detail.put("source", s.key);
            detail.put("sourceName", s.name);
            detail.put("id", v.optString("vod_id", ""));
            detail.put("name", v.optString("vod_name", ""));
            detail.put("pic", v.optString("vod_pic", ""));
            detail.put("actor", v.optString("vod_actor", ""));
            detail.put("director", v.optString("vod_director", ""));
            detail.put("blurb", v.optString("vod_blurb", ""));
            detail.put("year", v.optString("vod_year", ""));

            String playFrom = v.optString("vod_play_from", "");
            String playUrl = v.optString("vod_play_url", "");
            detail.put("lines", parsePlay(playFrom, playUrl));

            JSONObject resp = new JSONObject();
            resp.put("code", 0);
            resp.put("detail", detail);
            return jsonResponse(resp);
        } catch (Exception e) {
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "detail failed: " + e.getMessage());
            }
            return error("详情解析失败");
        }
    }

    /** 解析 vod_play_from / vod_play_url → 线路分组（对齐 TVBox App 实现）。 */
    private JSONArray parsePlay(String playFrom, String playUrl) throws JSONException {
        JSONArray lines = new JSONArray();
        if (TextUtils.isEmpty(playUrl)) {
            return lines;
        }
        String[] groups = splitByLines(playUrl);
        String[] flags = buildFlags(playFrom, groups.length);
        for (int i = 0; i < groups.length; i++) {
            String flag = (i < flags.length && !TextUtils.isEmpty(flags[i]))
                    ? flags[i].trim() : ("线路" + (i + 1));
            if (TextUtils.isEmpty(flag)) {
                flag = "线路" + (i + 1);
            }
            List<Ep> eps = parseEps(groups[i]);
            maybeReverse(eps);
            JSONArray epsArr = new JSONArray();
            for (Ep ep : eps) {
                JSONObject eo = new JSONObject();
                eo.put("name", ep.name);
                eo.put("url", ep.url);
                epsArr.put(eo);
            }
            JSONObject line = new JSONObject();
            line.put("flag", flag);
            line.put("eps", epsArr);
            lines.put(line);
        }
        return lines;
    }

    /** 线路分组分隔：标准 $$$，兼容历史 $$。 */
    private String[] splitByLines(String playUrl) {
        String[] groups = playUrl.split("\\$\\$\\$");
        if (groups.length == 1 && groups[0].contains("$$")) {
            groups = playUrl.split("\\$\\$");
        }
        return groups;
    }

    private String[] buildFlags(String playFrom, int groupCount) {
        String[] flags = new String[0];
        if (!TextUtils.isEmpty(playFrom)) {
            flags = playFrom.split("\\$\\$\\$");
            if (flags.length == 1 && flags[0].contains("$$")) {
                flags = playFrom.split("\\$\\$");
            }
        }
        return flags;
    }

    private List<Ep> parseEps(String group) {
        List<Ep> eps = new ArrayList<>();
        if (TextUtils.isEmpty(group)) {
            return eps;
        }
        String[] parts = group.split("#");
        int idx = 0;
        for (String p : parts) {
            if (TextUtils.isEmpty(p)) {
                continue;
            }
            String[] kv = p.split("\\$", 2);
            if (kv.length == 2 && !TextUtils.isEmpty(kv[0])) {
                eps.add(new Ep(kv[0], kv[1]));
            } else if (kv.length == 2) {
                idx++;
                eps.add(new Ep("第" + idx + "集", kv[1]));
            } else {
                idx++;
                eps.add(new Ep("第" + idx + "集", kv[0]));
            }
            idx = eps.size();
        }
        return eps;
    }

    /** 集名数字递减比例高时反转（兼容倒序源）。 */
    private void maybeReverse(List<Ep> eps) {
        if (eps.size() < 2) {
            return;
        }
        int cap = Math.min(6, eps.size() - 1);
        int down = 0, total = 0;
        for (int i = 0; i < cap; i++) {
            int a = extractNumber(eps.get(i).name);
            int b = extractNumber(eps.get(i + 1).name);
            if (a > 0 && b > 0) {
                total++;
                if (a > b) {
                    down++;
                }
            }
        }
        if (total > 0 && down * 2 >= total) {
            Collections.reverse(eps);
        }
    }

    private int extractNumber(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        if (sb.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 源更新（awesome-zhuiju-free）

    /** 启动时检查：超过 24h 未更新则后台拉取。 */
    private void maybeAutoUpdateOnStart() {
        JSONObject data = loadSources();
        long lastUpdated = data.optLong("lastUpdated", 0L);
        if (lastUpdated == 0 || System.currentTimeMillis() - lastUpdated > UPDATE_INTERVAL_MS) {
            triggerUpdate();
        }
    }

    private void triggerUpdate() {
        if (!updating.compareAndSet(false, true)) {
            return;
        }
        executor.submit(() -> {
            try {
                updateSourcesSync();
            } finally {
                updating.set(false);
            }
        });
    }

    /** 拉取 awesome-zhuiju-free → 扫描 tvbox 配置 → 提取 type=1 站点 → 探测 → 合并保存。 */
    private void updateSourcesSync() {
        long ts = System.currentTimeMillis();
        Map<String, JSONObject> merged = new HashMap<>();

        // 先载入已有源（保留用户启停状态与内置源）
        JSONObject existing = loadSources();
        JSONArray exArr = getSourcesArray(existing);
        for (int i = 0; i < exArr.length(); i++) {
            JSONObject o = exArr.optJSONObject(i);
            if (o != null) {
                merged.put(o.optString("key"), o);
            }
        }

        // 1. 下载资源清单（github raw，失败切 CDN）
        String rawResources = null;
        try {
            rawResources = HttpFetcher.fetch(RAW_RESOURCES_URL, null);
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(rawResources)) {
            try {
                rawResources = HttpFetcher.fetch(CDN_RESOURCES_URL, null);
            } catch (Exception ignored) {
            }
        }

        // 2. 收集 tvbox_config 配置地址
        List<ConfigRef> configs = new ArrayList<>();
        if (!TextUtils.isEmpty(rawResources)) {
            try {
                JSONObject resourcesRoot = new JSONObject(rawResources);
                JSONArray resources = resourcesRoot.optJSONArray("resources");
                if (resources != null) {
                    for (int i = 0; i < resources.length(); i++) {
                        JSONObject r = resources.optJSONObject(i);
                        if (r == null) {
                            continue;
                        }
                        if (!RESOURCES_CATEGORY_TVBOX.equals(r.optString("category", ""))) {
                            continue;
                        }
                        String url = r.optString("url", "");
                        if (TextUtils.isEmpty(url)) {
                            continue;
                        }
                        String status = "pending";
                        JSONObject verification = r.optJSONObject("verification");
                        if (verification != null) {
                            status = verification.optString("status", "pending");
                        }
                        configs.add(new ConfigRef(url, status));
                    }
                }
            } catch (JSONException ignored) {
            }
        }

        // 3. 逐个配置下载并提取 type=1 站点
        int scannedConfigs = 0;
        for (ConfigRef c : configs) {
            if (isRemoved(c.status)) {
                continue; // 配置级 removed / temporarily_unavailable：跳过
            }
            try {
                String configBody = HttpFetcher.fetch(c.url, null, 6000, 6000);
                if (TextUtils.isEmpty(configBody)) {
                    continue;
                }
                JSONObject config = new JSONObject(configBody);
                JSONArray sites = config.optJSONArray("sites");
                if (sites == null) {
                    continue;
                }
                scannedConfigs++;
                for (int i = 0; i < sites.length(); i++) {
                    JSONObject site = sites.optJSONObject(i);
                    if (site == null) {
                        continue;
                    }
                    int type = site.optInt("type", 0);
                    if (type != 1) {
                        continue; // 仅直连型苹果CMS
                    }
                    String api = site.optString("api", "");
                    if (TextUtils.isEmpty(api)
                            || !(api.startsWith("http://") || api.startsWith("https://"))) {
                        continue;
                    }
                    String key = "s" + Integer.toHexString(api.hashCode());
                    JSONObject s = merged.get(key);
                    if (s == null) {
                        s = new JSONObject();
                        try {
                            s.put("key", key);
                            s.put("name", site.optString("name", api));
                            s.put("api", api);
                            s.put("enable", true);
                            s.put("builtin", false);
                            s.put("createdAt", ts);
                        } catch (JSONException ignored) {
                        }
                        merged.put(key, s);
                    }
                    if (s.optString("status", "").isEmpty() || "pending".equals(s.optString("status", ""))) {
                        try {
                            s.put("status", c.status);
                        } catch (JSONException ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
                // 单个配置失败不影响整体
            }
        }

        // 4. 对站点做可达性探测
        for (JSONObject s : merged.values()) {
            probe(s);
        }

        // 5. 保存
        try {
            JSONObject data = new JSONObject();
            data.put("version", 1);
            data.put("lastUpdated", ts);
            data.put("lastScan", scannedConfigs);
            JSONArray out = new JSONArray();
            for (JSONObject s : merged.values()) {
                out.put(s);
            }
            data.put("sources", out);
            saveSources(data);
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "movie sources updated: total=" + out.length()
                        + ", configs=" + scannedConfigs);
            }
        } catch (JSONException ignored) {
        }
    }

    private boolean isRemoved(String status) {
        return "removed".equals(status) || "temporarily_unavailable".equals(status);
    }

    /** 站点可达性探测：GET api 根（短超时），成功即认为可达。 */
    private void probe(JSONObject s) {
        String api = s.optString("api", "");
        if (TextUtils.isEmpty(api)) {
            return;
        }
        try {
            String body = HttpFetcher.fetch(api, null, 5000, 5000);
            if (!TextUtils.isEmpty(body)) {
                String cur = s.optString("status", "");
                if (TextUtils.isEmpty(cur) || "pending".equals(cur)) {
                    s.put("status", "recommended");
                }
                s.put("lastProbeError", "");
            } else {
                s.put("status", "caution");
                s.put("lastProbeError", "空响应");
            }
        } catch (Exception e) {
            s.put("status", "caution");
            s.put("lastProbeError", e.getMessage() != null ? e.getMessage() : "探测失败");
        }
    }

    // ---------------------------------------------------------------- 内部类

    private static class Source {
        final String key;
        final String name;
        final String api;

        Source(String key, String name, String api) {
            this.key = key;
            this.name = name;
            this.api = api;
        }
    }

    private static class ConfigRef {
        final String url;
        final String status;

        ConfigRef(String url, String status) {
            this.url = url;
            this.status = status;
        }
    }

    private static class Ep {
        final String name;
        final String url;

        Ep(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static NanoHTTPD.Response jsonResponse(JSONObject obj) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8", obj.toString());
    }

    private static JSONObject message(String code, String msg) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("code", code);
        o.put("msg", msg);
        return o;
    }

    private static NanoHTTPD.Response error(String msg) {
        try {
            return jsonResponse(message("error", msg));
        } catch (JSONException e) {
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK,
                    "{\"code\":\"error\",\"msg\":\"" + msg + "\"}");
        }
    }
}