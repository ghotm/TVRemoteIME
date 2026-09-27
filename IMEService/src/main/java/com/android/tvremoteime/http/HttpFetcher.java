package com.android.tvremoteime.http;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 轻量级 HTTP 抓取工具（影视仓用）。
 *
 * 特性：
 * - 支持 http + https（不复用强制 Https 的 HTTPGet）；
 * - 连接/读取双超时；
 * - 手动跟随重定向（含 http→https 跨协议、相对地址解析），每跳重新做 SSRF 校验；
 * - gzip 解压；
 * - 中文 IDN 域名归一化；
 * - 仅允许 http/https，并阻断私网/环回/链路本地地址（SSRF 基础防护）；
 * - 不使用全局 trust-all（HTTPSTrustManager），避免扩大 MITM 面。
 */
public class HttpFetcher {

    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_REDIRECTS = 5;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 8000;
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024; // 8MB 上限

    public static String fetch(String urlString) throws IOException {
        return fetch(urlString, null, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public static String fetch(String urlString, Map<String, String> extraHeaders) throws IOException {
        return fetch(urlString, extraHeaders, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public static String fetch(String urlString, Map<String, String> extraHeaders,
                               int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String current = normalizeIdn(urlString);
        // 第一跳 SSRF 校验
        checkPublicUrl(current);

        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false); // 手动控制重定向
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", DEFAULT_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (!TextUtils.isEmpty(e.getKey()) && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (TextUtils.isEmpty(location)) {
                    throw new IOException("Redirect without Location at " + current);
                }
                // 相对/绝对解析（base.toURI/resolve 会抛受检的 URISyntaxException）
                URL base = new URL(current);
                try {
                    current = normalizeIdn(base.toURI().resolve(location).toString());
                } catch (java.net.URISyntaxException e) {
                    throw new IOException("Invalid redirect location: " + location, e);
                }
                checkPublicUrl(current);
                continue;
            }

            try {
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (is == null) {
                    throw new IOException("HTTP " + code + " (no body) at " + current);
                }
                if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                    is = new GZIPInputStream(is);
                }
                String charset = parseCharset(conn.getContentType());
                byte[] bytes = readLimited(is);
                if (bytes == null) {
                    throw new IOException("Response too large (>8MB) at " + current);
                }
                return new String(bytes, Charset.forName(charset));
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] readLimited(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    /** 中文 IDN 域名转 ASCII（如 http://肥猫.net/ → http://xn--.../）。 */
    private static String normalizeIdn(String urlString) throws IOException {
        try {
            URL u = new URL(urlString);
            String host = u.getHost();
            if (host != null && !isAscii(host)) {
                String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
                return urlString.replace(host, ascii);
            }
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + urlString, e);
        }
        return urlString;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** SSRF 基础防护：仅 http/https，解析后阻断私网/环回/链路本地地址。 */
    private static void checkPublicUrl(String urlString) throws IOException {
        URL u;
        try {
            u = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new IOException("Invalid URL: " + urlString, e);
        }
        String proto = u.getProtocol();
        if (proto == null || !(proto.equalsIgnoreCase("http") || proto.equalsIgnoreCase("https"))) {
            throw new IOException("Unsupported protocol: " + proto);
        }
        String host = u.getHost();
        if (TextUtils.isEmpty(host)) {
            throw new IOException("Invalid host in: " + urlString);
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress()
                        || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                    throw new IOException("Blocked non-public address: " + host + " -> " + a.getHostAddress());
                }
            }
        } catch (IOException e) {
            if (e instanceof java.net.UnknownHostException) {
                throw e;
            }
            throw new IOException("Resolve failed for " + host + ": " + e.getMessage(), e);
        }
    }

    private static String parseCharset(String contentType) {
        if (!TextUtils.isEmpty(contentType)) {
            String lower = contentType.toLowerCase();
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String cs = contentType.substring(idx + "charset=".length()).trim();
                int semi = cs.indexOf(';');
                if (semi >= 0) {
                    cs = cs.substring(0, semi).trim();
                }
                if (!TextUtils.isEmpty(cs)) {
                    try {
                        Charset.forName(cs);
                        return cs;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return "UTF-8";
    }
}