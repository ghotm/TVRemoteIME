package com.android.tvremoteime.server;

import android.text.TextUtils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将外部直播列表（M3U / TVBox txt）转换为本项目 tv.txt 格式，并支持多源合并。
 *
 * 输出格式与 ime_core.js 的 parseTVData 严格对齐：
 *   [分组名]
 *   频道名=url
 *   频道名(2)=url2
 * 其中方括号是分组，组内每行「频道名=地址」。
 *
 * 合并（merge）：按源分区 —— 分组名 = `源名 | 原始分组`，不同源的同名频道不会被聚合。
 */
public class LiveListConverter {

    private LiveListConverter() {
    }

    /** 转换结果。 */
    public static final class Result {
        public final String text;      // tv.txt 内容（UTF-8）
        public final int channelCount; // 频道数（去重后）
        public final int sourceCount;  // 源（url）总数
        public final String format;    // "m3u" / "txt" / "merge"

        Result(String text, int channelCount, int sourceCount, String format) {
            this.text = text;
            this.channelCount = channelCount;
            this.sourceCount = sourceCount;
            this.format = format;
        }
    }

    /** 合并时的单个源输入。 */
    public static final class Source {
        public final String key;
        public final String name;
        public final byte[] data;
        public final String charset;

        public Source(String key, String name, byte[] data, String charset) {
            this.key = key;
            this.name = name;
            this.data = data;
            this.charset = charset;
        }
    }

    /** 一个频道（同名同组频道已合并多个 url）。 */
    public static final class ChannelEntry {
        public final String name;
        public final List<String> urls = new ArrayList<>();

        ChannelEntry(String name) {
            this.name = name;
        }
    }

    /** 一个分组：分组名 + 组内频道列表。 */
    public static final class Group {
        public final String name;
        public final List<ChannelEntry> channels = new ArrayList<>();

        Group(String name) {
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

    /** 解析为结构化分组列表（自动识别 M3U / txt）。 */
    public static List<Group> parse(byte[] data, String declaredCharset) {
        String text = decode(data, declaredCharset);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) {
            return parseM3u(text);
        }
        return parseTxt(text);
    }

    /** 自动识别格式并转换：以 #EXTM3U / #EXTINF 开头按 M3U，否则按 TVBox txt。 */
    public static Result convert(byte[] data, String declaredCharset) {
        List<Group> groups = parse(data, declaredCharset);
        return build(groups, detectFormat(data, declaredCharset));
    }

    private static String detectFormat(byte[] data, String declaredCharset) {
        String text = decode(data, declaredCharset);
        String trimmed = text.trim();
        return (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) ? "m3u" : "txt";
    }

    /** 合并多个源为一个列表：按源分区（分组名 = `源名 | 原始分组`），同名源加 (2)/(3) 保证唯一。 */
    public static Result merge(List<Source> inputs) {
        List<String> used = new ArrayList<>();
        List<Group> out = new ArrayList<>();
        for (Source src : inputs) {
            String base = clean(src.name);
            if (base.isEmpty()) {
                base = "源";
            }
            String disp = base;
            int n = 2;
            while (used.contains(disp)) {
                disp = base + "(" + n + ")";
                n++;
            }
            used.add(disp);
            List<Group> groups;
            try {
                groups = parse(src.data, src.charset);
            } catch (Exception e) {
                continue;
            }
            for (Group g : groups) {
                Group ng = new Group(disp + " | " + clean(g.name));
                ng.channels.addAll(g.channels);
                out.add(ng);
            }
        }
        return build(out, "merge");
    }

    /** 清洗分组名/频道名中的特殊字符，防止破坏 tv.txt 的 `[分组]` 与 `频道名=url` 行。 */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("[", "(").replace("]", ")").replace("=", "＝").replace("\r", " ").replace("\n", " ");
    }

    // ------------------------------------------------------------------ M3U

    private static List<Group> parseM3u(String text) {
        LinkedHashMap<String, ChannelEntry> channels = new LinkedHashMap<>();
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
        return toGroups(channels);
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

    private static List<Group> parseTxt(String text) {
        LinkedHashMap<String, ChannelEntry> channels = new LinkedHashMap<>();
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
            ChannelEntry ch = getOrCreate(channels, group, name);
            for (String u : rest.split("#")) {
                String url = u.trim();
                if (!url.isEmpty()) {
                    ch.urls.add(url);
                }
            }
        }
        return toGroups(channels);
    }

    // ------------------------------------------------------------------ 公共

    private static ChannelEntry getOrCreate(LinkedHashMap<String, ChannelEntry> channels, String group, String name) {
        String key = group + "\u0000" + name;
        ChannelEntry ch = channels.get(key);
        if (ch == null) {
            ch = new ChannelEntry(name);
            channels.put(key, ch);
        }
        return ch;
    }

    /** 把按 (group,name) 去重后的频道映射分组为 List<Group>，保持首次出现顺序。 */
    private static List<Group> toGroups(LinkedHashMap<String, ChannelEntry> channels) {
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        for (Map.Entry<String, ChannelEntry> e : channels.entrySet()) {
            String key = e.getKey();
            int sep = key.indexOf('\u0000');
            String groupName = sep >= 0 ? key.substring(0, sep) : "直播";
            Group g = groups.get(groupName);
            if (g == null) {
                g = new Group(groupName);
                groups.put(groupName, g);
            }
            g.channels.add(e.getValue());
        }
        return new ArrayList<>(groups.values());
    }

    private static Result build(List<Group> groups, String format) {
        StringBuilder sb = new StringBuilder();
        int channelCount = 0;
        int sourceCount = 0;
        boolean firstGroup = true;
        for (Group g : groups) {
            if (!firstGroup) {
                sb.append("\n");
            }
            firstGroup = false;
            sb.append("[").append(clean(g.name)).append("]\n");
            for (ChannelEntry ch : g.channels) {
                int idx = 0;
                for (String url : ch.urls) {
                    idx++;
                    String label = idx == 1 ? clean(ch.name) : clean(ch.name) + "(" + idx + ")";
                    sb.append(label).append("=").append(url).append("\n");
                    sourceCount++;
                }
                channelCount++;
            }
        }
        return new Result(sb.toString(), channelCount, sourceCount, format);
    }
}
