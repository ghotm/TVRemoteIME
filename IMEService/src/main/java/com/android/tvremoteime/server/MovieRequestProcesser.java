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
                case "/movie/addSource":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        return doAddSource(params.get("name"), params.get("api"));
                    }
                    break;
                case "/movie/removeSource":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        return doRemoveSource(params.get("key"));
                    }
                    break;
                case "/movie/reseed":
                    if (session.getMethod() == NanoHTTPD.Method.POST) {
                        return doReseed();
                    }
                    break;
                case "/movie/playState":
                    if (session.getMethod() == NanoHTTPD.Method.GET) {
                        JSONObject o = new JSONObject();
                        o.put("isPlaying", VideoPlayHelper.isPlaying());
                        o.put("lastPlayUrl", VideoPlayHelper.getLastPlayUrl() != null
                                ? VideoPlayHelper.getLastPlayUrl() : "");
                        o.put("lastPlayError", VideoPlayHelper.getLastPlayError() != null
                                ? VideoPlayHelper.getLastPlayError() : "");
                        o.put("lastPlayErrorTime", VideoPlayHelper.getLastPlayErrorTime());
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
            data.put("removedApis", new JSONArray());
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

    // ---------------------------------------------------------------- 源增删（手动维护）

    /** 手动添加自定义源：{name, api}。增强探测通过才保存，失败返回具体原因。 */
    private NanoHTTPD.Response doAddSource(String name, String api) {
        if (TextUtils.isEmpty(name)) {
            return error("缺少名称 name");
        }
        if (TextUtils.isEmpty(api)) {
            return error("缺少接口地址 api");
        }
        final String napi = normalizeApi(api);
        if (TextUtils.isEmpty(napi)) {
            return error("接口地址无效");
        }
        synchronized (this) {
            JSONObject data = loadSources();
            JSONObject exist = findByApi(data, napi);
            if (exist != null) {
                return error("该接口已存在（" + exist.optString("name", "") + "）");
            }
            // 增强探测：{api}?ac=detail&wd=test 响应须含 list（在锁内串行执行，单用户可接受）
            String probeErr = probeAdd(napi);
            if (probeErr != null) {
                return error("添加失败：" + probeErr);
            }
            try {
                String key = uniqueKey(data, napi);
                JSONObject s = new JSONObject();
                s.put("key", key);
                s.put("name", name.trim());
                s.put("api", napi);
                s.put("enable", true);
                s.put("status", "recommended");
                s.put("lastProbeError", "");
                s.put("builtin", false);
                s.put("createdAt", System.currentTimeMillis());
                getSourcesArray(data).put(s);
                removeFromRemovedApis(data, napi); // 重加撤销墓碑
                saveSources(data);
                return jsonResponse(message("ok", "已添加"));
            } catch (JSONException e) {
                return error("json_error");
            }
        }
    }

    /** 删除源：{key}。写入墓碑集，自动更新不会复活。 */
    private NanoHTTPD.Response doRemoveSource(String key) {
        if (TextUtils.isEmpty(key)) {
            return error("缺少 key");
        }
        synchronized (this) {
            JSONObject data = loadSources();
            JSONArray arr = getSourcesArray(data);
            JSONObject found = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && key.equals(o.optString("key"))) {
                    found = o;
                    arr.remove(i);
                    break;
                }
            }
            if (found == null) {
                return error("源不存在");
            }
            String napi = normalizeApi(found.optString("api", ""));
            if (!TextUtils.isEmpty(napi)) {
                addToRemovedApis(data, napi);
            }
            saveSources(data);
        }
        try {
            return jsonResponse(message("ok", "已删除"));
        } catch (JSONException e) {
            return error("json_error");
        }
    }

    /** 恢复内置源：按归一化 api 匹配命中复用，未命中以固定 key 补回；同时撤销内置源墓碑。 */
    private NanoHTTPD.Response doReseed() {
        synchronized (this) {
            JSONObject data = loadSources();
            for (String[] b : BUILTIN_SOURCES) {
                String bApi = b[2];
                String napi = normalizeApi(bApi);
                JSONObject exist = findByApi(data, bApi);
                if (exist != null) {
                    try {
                        exist.put("enable", true);
                    } catch (JSONException ignored) {
                    }
                } else {
                    try {
                        JSONObject s = new JSONObject();
                        s.put("key", b[0]);
                        s.put("name", b[1]);
                        s.put("api", bApi);
                        s.put("enable", true);
                        s.put("status", "recommended");
                        s.put("lastProbeError", "");
                        s.put("builtin", true);
                        s.put("createdAt", System.currentTimeMillis());
                        getSourcesArray(data).put(s);
                    } catch (JSONException ignored) {
                    }
                }
                if (!TextUtils.isEmpty(napi)) {
                    removeFromRemovedApis(data, napi);
                }
            }
            saveSources(data);
        }
        try {
            return jsonResponse(message("ok", "已恢复内置源"));
        } catch (JSONException e) {
            return error("json_error");
        }
    }

    // ---------------------------------------------------------------- 源工具（归一化 / 墓碑集）

    /** 归一化接口地址：去除首尾空白与尾部斜杠。 */
    private static String normalizeApi(String api) {
        if (api == null) {
            return "";
        }
        String s = api.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 扫描/添加源的默认 key：归一化 api 的 hashCode 十六进制。 */
    private static String apiKey(String api) {
        return "s" + Integer.toHexString(normalizeApi(api).hashCode());
    }

    /** 按归一化 api 在源清单中查找（去重唯一依据，内置源与扫描源互认）。 */
    private JSONObject findByApi(JSONObject data, String api) {
        String napi = normalizeApi(api);
        if (TextUtils.isEmpty(napi)) {
            return null;
        }
        JSONArray arr = getSourcesArray(data);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && napi.equals(normalizeApi(o.optString("api", "")))) {
                return o;
            }
        }
        return null;
    }

    /** 墓碑集（已删除的归一化 api）：自动更新扫描时永久跳过。空安全。 */
    private Set<String> getRemovedApis(JSONObject data) {
        Set<String> set = new HashSet<>();
        JSONArray arr = data.optJSONArray("removedApis");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String a = arr.optString(i, "");
                if (!TextUtils.isEmpty(a)) {
                    set.add(a);
                }
            }
        }
        return set;
    }

    private void addToRemovedApis(JSONObject data, String napi) {
        if (TextUtils.isEmpty(napi)) {
            return;
        }
        try {
            JSONArray arr = data.optJSONArray("removedApis");
            if (arr == null) {
                arr = new JSONArray();
                data.put("removedApis", arr);
            }
            for (int i = 0; i < arr.length(); i++) {
                if (napi.equals(arr.optString(i, ""))) {
                    return;
                }
            }
            arr.put(napi);
        } catch (JSONException ignored) {
        }
    }

    private void removeFromRemovedApis(JSONObject data, String napi) {
        if (TextUtils.isEmpty(napi)) {
            return;
        }
        JSONArray arr = data.optJSONArray("removedApis");
        if (arr == null) {
            return;
        }
        for (int i = arr.length() - 1; i >= 0; i--) {
            if (napi.equals(arr.optString(i, ""))) {
                arr.remove(i);
            }
        }
    }

    /** 生成不与现有 key 冲突的源 key（调用方须持有 synchronized(this)）。 */
    private String uniqueKey(JSONObject data, String napi) {
        String base = apiKey(napi);
        Set<String> keys = new HashSet<>();
        JSONArray arr = getSourcesArray(data);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                String k = o.optString("key", "");
                if (!TextUtils.isEmpty(k)) {
                    keys.add(k);
                }
            }
        }
        if (!keys.contains(base)) {
            return base;
        }
        int i = 1;
        while (keys.contains(base + "_" + i)) {
            i++;
        }
        return base + "_" + i;
    }

    // ---------------------------------------------------------------- 搜索 / 详情

    private NanoHTTPD.Response doSearch(String wd) {
        if (TextUtils.isEmpty(wd)) {
            return error("缺少关键词 wd");
        }
        final String keyword = wd.trim();
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
            Future<JSONArray> f = pool.submit(() -> querySource(s, keyword));
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

    /** 拉取 awesome-zhuiju-free → 扫描 tvbox 配置 → 提取 type=1 站点 → 三段式合并保存。
     *  ① 锁外扫描收集新增候选（按归一化 api 去重、跳过墓碑与已有源）
     *  ② 锁外并发探测全部源（现有 + 候选），刷新 status/lastProbeError
     *  ③ 锁内以最新文件为权威基线合并保存（不动 enable、不复活已删项） */
    private void updateSourcesSync() {
        long ts = System.currentTimeMillis();

        // 基于当前文件快照（权威合并以 ③ 锁内重读的最新文件为准）
        JSONObject snapshot = loadSources();

        // --- ① 锁外扫描：配置收集 → 候选站点提取 ---
        List<ConfigRef> configs = collectConfigs();
        List<JSONObject> probing = new ArrayList<>();          // ② 需要探测的对象：现有源 + 候选
        Map<String, JSONObject> candidates = new HashMap<>();  // 真正新增候选：key -> obj
        JSONArray exArr = getSourcesArray(snapshot);
        for (int i = 0; i < exArr.length(); i++) {
            JSONObject o = exArr.optJSONObject(i);
            if (o != null) {
                probing.add(o);
            }
        }
        Set<String> removedSet0 = getRemovedApis(snapshot);
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
                    String napi = normalizeApi(api);
                    if (TextUtils.isEmpty(napi)
                            || !(napi.startsWith("http://") || napi.startsWith("https://"))) {
                        continue;
                    }
                    // 已存在（含内置源同 api）→ 不新增；已在墓碑集 → 永久跳过
                    if (findByApi(snapshot, napi) != null || removedSet0.contains(napi)) {
                        continue;
                    }
                    String key = apiKey(napi);
                    if (!candidates.containsKey(key)) {
                        JSONObject s = new JSONObject();
                        try {
                            s.put("key", key);
                            s.put("name", site.optString("name", api));
                            s.put("api", api);
                            s.put("status", c.status);
                        } catch (JSONException ignored) {
                        }
                        candidates.put(key, s);
                        probing.add(s);
                    }
                }
            } catch (Exception ignored) {
                // 单个配置失败不影响整体
            }
        }

        // --- ② 锁外并发探测：刷新 status / lastProbeError（现有源状态不再冻结） ---
        if (!probing.isEmpty()) {
            int poolSize = Math.min(probing.size(), 4);
            ExecutorService pool = Executors.newFixedThreadPool(poolSize);
            List<Future<?>> futures = new ArrayList<>();
            for (JSONObject s : probing) {
                futures.add(pool.submit(() -> probe(s)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    f.cancel(true);
                }
            }
            pool.shutdownNow();
        }

        // --- ③ 锁内合并保存：权威基线 = 最新文件（含扫描期间用户增删） ---
        synchronized (this) {
            JSONObject latest = loadSources();
            JSONArray latestArr = getSourcesArray(latest);
            // 以快照探测结果为准：仅覆盖 ② probe 过的 key（不动 enable、不复活已删项）
            Map<String, JSONObject> snapByKey = new HashMap<>();
            for (JSONObject o : probing) {
                snapByKey.put(o.optString("key"), o);
            }
            for (int i = 0; i < latestArr.length(); i++) {
                JSONObject o = latestArr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                JSONObject snap = snapByKey.get(o.optString("key"));
                if (snap == null) {
                    continue; // 扫描期间用户新增的源：保持原值
                }
                try {
                    o.put("status", snap.optString("status", "pending"));
                    o.put("lastProbeError", snap.optString("lastProbeError", ""));
                } catch (JSONException ignored) {
                }
            }
            // 并入真正新增项：findByApi(latest) 为 null 且非最新墓碑
            Set<String> removedSet1 = getRemovedApis(latest);
            for (JSONObject c : candidates.values()) {
                String napi = normalizeApi(c.optString("api", ""));
                if (TextUtils.isEmpty(napi)) {
                    continue;
                }
                if (findByApi(latest, napi) != null || removedSet1.contains(napi)) {
                    continue;
                }
                try {
                    String finalKey = uniqueKey(latest, napi);
                    JSONObject s = new JSONObject();
                    s.put("key", finalKey);
                    s.put("name", c.optString("name", napi));
                    s.put("api", c.optString("api"));
                    s.put("enable", true);
                    s.put("builtin", false);
                    s.put("createdAt", ts);
                    s.put("status", c.optString("status", "pending"));
                    s.put("lastProbeError", c.optString("lastProbeError", ""));
                    latestArr.put(s);
                } catch (JSONException ignored) {
                }
            }
            try {
                latest.put("version", 1);
                latest.put("lastUpdated", ts);
                latest.put("lastScan", scannedConfigs);
                saveSources(latest);
            } catch (JSONException ignored) {
            }
            if (Environment.needDebug) {
                Environment.debug(IMEService.TAG, "movie sources updated: total=" + latestArr.length()
                        + ", configs=" + scannedConfigs);
            }
        }
    }

    /** 下载 awesome-zhuiju-free → 收集 tvbox_config 配置地址（github raw 失败切 CDN）。 */
    private List<ConfigRef> collectConfigs() {
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
        return configs;
    }

    private boolean isRemoved(String status) {
        return "removed".equals(status) || "temporarily_unavailable".equals(status);
    }

    /** 增强探测（手动添加源用）：{api}?ac=detail&wd=test 响应须含 list 字段。
     *  返回 null 表示通过，否则返回失败原因。 */
    private String probeAdd(String api) {
        String sep = api.contains("?") ? "&" : "?";
        String url = api + sep + "ac=detail&wd=test";
        try {
            String body = HttpFetcher.fetch(url, null, 8000, 8000);
            if (TextUtils.isEmpty(body)) {
                return "无响应";
            }
            JSONObject root = new JSONObject(body);
            if (!root.has("list")) {
                return "响应不是标准影视接口格式（缺少 list 字段）";
            }
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Blocked non-public address")) {
                return "被安全策略拒绝（不支持内网/私有地址）";
            }
            if (msg != null && msg.contains("Unsupported protocol")) {
                return "仅支持 http/https 协议";
            }
            return msg != null ? msg : "请求失败";
        }
    }

    /** 站点可达性探测：GET api 根（短超时），成功即认为可达。 */
    private void probe(JSONObject s) {
        String api = s.optString("api", "");
        if (TextUtils.isEmpty(api)) {
            return;
        }
        String status;
        String probeError = "";
        try {
            String body = HttpFetcher.fetch(api, null, 5000, 5000);
            if (!TextUtils.isEmpty(body)) {
                String cur = s.optString("status", "");
                if (TextUtils.isEmpty(cur) || "pending".equals(cur)) {
                    status = "recommended";
                } else {
                    status = cur;
                }
            } else {
                status = "caution";
                probeError = "空响应";
            }
        } catch (Exception e) {
            status = "caution";
            probeError = e.getMessage() != null ? e.getMessage() : "探测失败";
        }
        try {
            s.put("status", status);
            s.put("lastProbeError", probeError);
        } catch (JSONException ignored) {
            // 状态字段写不进去也不影响整体
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