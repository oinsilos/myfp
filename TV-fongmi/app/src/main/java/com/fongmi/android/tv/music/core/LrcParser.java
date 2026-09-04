package com.fongmi.android.tv.music.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 解析（纯逻辑，无 Android 依赖）：标准格式 {@code [mm:ss.xx]text}，
 * 支持一行多时间戳（取最后一个）、无时间戳行跳过；结果按时间升序。
 * 供歌词 UI 与通知栏歌词共用。
 */
public final class LrcParser {

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    public static final class Line {
        public final long timeMs;
        public final String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private LrcParser() {
    }

    public static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return lines;
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = TIME.matcher(line);
            long time = -1;
            int lastEnd = 0;
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) * 60_000L + Long.parseLong(m.group(2)) * 1000L;
                String frac = m.group(3);
                if (frac != null) {
                    t += switch (frac.length()) {
                        case 1 -> Long.parseLong(frac) * 100L;
                        case 2 -> Long.parseLong(frac) * 10L;
                        default -> Long.parseLong(frac);
                    };
                }
                if (t > time) time = t;
                lastEnd = m.end();
            }
            if (time < 0) continue;
            String text = (lastEnd >= line.length()) ? "" : line.substring(lastEnd).trim();
            lines.add(new Line(time, text));
        }
        lines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return lines;
    }

    /** 二分定位 positionMs 落在哪一句（上一句未结束则返回前一句）。 */
    public static int indexOf(List<Line> lines, long positionMs) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}