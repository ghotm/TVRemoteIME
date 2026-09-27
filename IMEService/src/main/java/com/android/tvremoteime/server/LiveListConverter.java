package com.android.tvremoteime.server;

import android.text.TextUtils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将外部直播列表（M3U / TVBox txt）转换为本项目 tv.txt 格式。
 *
 * 输出格式与 ime_core.js 的 parseTVData 严格对齐：
 *   [分组名]
 *   频道名=url
 *   频道名(2)=url2
 * 其中方括号是分组，组内每行「频道名=地址」。
 */
public class LiveListConverter {

    private LiveListConverter() {
    }

    /** 转换结果。 */
    public static final class Result {
        public final String text;      // tv.txt 内容（UTF-8）
        public final int channelCount; // 频道数（去重后）
        public final int sourceCount;  // 源（url）总数
        public final String format;    // "m3u" 或 "txt"

        Result(String text, int channelCount, int sourceCount, String format) {
            this.text = text;
            this.channelCount = channelCount;
            this.sourceCount = sourceCount;
            this.format = format;
        }
    }

    private static final class Channel {
        final String group;
        final String name;
        final List<String> urls = new ArrayList<>();

        Channel(String group, String name) {
            this.group = group;
            this.name = name;
        }
    }

    /**
     * 从原始字节解码为文本：候选编码为「声明的 charset → UTF-8 → GBK」，
     * 选择解码后 U+FFFD 替换字符最少的结果。
     */
    public static String decode(byte[] data, String declaredCharset) {
        List<String> candidates = new ArrayList<>();
        if (!TextUtils.isEmpty(declaredCharset)) {
            candidates.add(declaredCharset);
        }
        candidates.add("UTF-8");
        candidates.add("GBK");

        String best = null;
        int bestBad = Integer.MAX_VALUE;
        for (String cs : candidates) {
            String decoded;
            try {
                decoded = new String(data, Charset.forName(cs));
            } catch (Exception e) {
                continue;
            }
            int bad = countReplacement(decoded);
            if (bad < bestBad) {
                bestBad = bad;
                best = decoded;
                if (bad == 0) {
                    break;
                }
            }
        }
        if (best == null) {
            best = new String(data, Charset.forName("UTF-8"));
        }
        return best;
    }

    private static int countReplacement(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') {
                c++;
            }
        }
        return c;
    }

    /** 自动识别格式并转换：以 #EXTM3U / #EXTINF 开头按 M3U，否则按 TVBox txt。 */
    public static Result convert(byte[] data, String declaredCharset) {
        String text = decode(data, declaredCharset);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) {
            return convertM3u(text);
        }
        return convertTxt(text);
    }

    // ------------------------------------------------------------------ M3U

    private static Result convertM3u(String text) {
        LinkedHashMap<String, Channel> channels = new LinkedHashMap<>();
        String group = "直播";
        String pendingName = null;
        int bareCount = 0;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                String g = extractAttr(line, "group-title");
                group = TextUtils.isEmpty(g) ? "直播" : g;
                pendingName = extractM3uName(line);
                if (TextUtils.isEmpty(pendingName)) {
                    pendingName = "频道";
                }
            } else if (line.startsWith("#")) {
                // 其它指令（#EXTVLCOPT / #KODIPROP 等）忽略
                continue;
            } else {
                String name = pendingName;
                if (TextUtils.isEmpty(name)) {
                    bareCount++;
                    name = "源" + bareCount;
                }
                getOrCreate(channels, group, name).urls.add(line);
                pendingName = null;
            }
        }
        return build(channels, "m3u");
    }

    /** 频道名取 #EXTINF 行最后一个逗号之后的内容；无逗号时退回 tvg-name。 */
    private static String extractM3uName(String line) {
        int comma = line.lastIndexOf(',');
        if (comma >= 0 && comma + 1 < line.length()) {
            String name = line.substring(comma + 1).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return extractAttr(line, "tvg-name");
    }

    /** 提取 M3U 属性值（key="X" 或 key=X），未找到返回空串。 */
    private static String extractAttr(String line, String key) {
        int idx = line.indexOf(key + "=");
        if (idx < 0) {
            return "";
        }
        int p = idx + key.length() + 1;
        if (p >= line.length()) {
            return "";
        }
        char q = line.charAt(p);
        if (q == '"' || q == '\'') {
            int end = line.indexOf(q, p + 1);
            if (end > p) {
                return line.substring(p + 1, end).trim();
            }
            return "";
        }
        int end = line.length();
        int space = line.indexOf(' ', p);
        if (space >= 0 && space < end) {
            end = space;
        }
        int comma = line.indexOf(',', p);
        if (comma >= 0 && comma < end) {
            end = comma;
        }
        return line.substring(p, end).trim();
    }

    // ------------------------------------------------------------- TVBox txt

    private static Result convertTxt(String text) {
        LinkedHashMap<String, Channel> channels = new LinkedHashMap<>();
        String group = "直播";

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#genre#")) {
                continue;
            }
            if (line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            int comma = line.indexOf(',');
            if (comma < 0) {
                continue;
            }
            String name = line.substring(0, comma).trim();
            String rest = line.substring(comma + 1).trim();
            if ("#genre#".equalsIgnoreCase(rest)) {
                // 分组行：分组名,#genre#
                if (!name.isEmpty()) {
                    group = name;
                }
                continue;
            }
            if (name.isEmpty() || rest.isEmpty()) {
                continue;
            }
            Channel ch = getOrCreate(channels, group, name);
            for (String u : rest.split("#")) {
                String url = u.trim();
                if (!url.isEmpty()) {
                    ch.urls.add(url);
                }
            }
        }
        return build(channels, "txt");
    }

    // ------------------------------------------------------------------ 公共

    private static Channel getOrCreate(LinkedHashMap<String, Channel> channels, String group, String name) {
        String key = group + "\u0000" + name;
        Channel ch = channels.get(key);
        if (ch == null) {
            ch = new Channel(group, name);
            channels.put(key, ch);
        }
        return ch;
    }

    private static Result build(LinkedHashMap<String, Channel> channels, String format) {
        // 按分组聚合，保持首次出现顺序
        LinkedHashMap<String, List<Channel>> groups = new LinkedHashMap<>();
        for (Channel ch : channels.values()) {
            List<Channel> list = groups.get(ch.group);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(ch.group, list);
            }
            list.add(ch);
        }

        StringBuilder sb = new StringBuilder();
        int channelCount = 0;
        int sourceCount = 0;
        boolean firstGroup = true;
        for (Map.Entry<String, List<Channel>> e : groups.entrySet()) {
            if (!firstGroup) {
                sb.append("\n");
            }
            firstGroup = false;
            sb.append("[").append(e.getKey()).append("]\n");
            for (Channel ch : e.getValue()) {
                int idx = 0;
                for (String url : ch.urls) {
                    idx++;
                    String label = idx == 1 ? ch.name : ch.name + "(" + idx + ")";
                    sb.append(label).append("=").append(url).append("\n");
                    sourceCount++;
                }
                channelCount++;
            }
        }
        return new Result(sb.toString(), channelCount, sourceCount, format);
    }
}
