package com.fongmi.android.tv.reader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 极简 EPUB(2/3) 解析：按 OPF spine 顺序提取每个内容页正文文本，供本地导入切章用。
 * 只读 text 类内容，忽略导航/字体/图片资源。
 */
public final class EpubImporter {

    /** 一章：标题 + 正文纯文本（段落以换行分隔）。 */
    public static final class Chapter {
        public final String title;
        public final String text;

        Chapter(String title, String text) {
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }
    }

    private static final int MAX_ENTRY = 8 * 1024 * 1024;

    /** 解析输入流中的 EPUB，按阅读顺序返回章节；无内容页返回空列表。 */
    public static List<Chapter> parse(InputStream in) {
        List<Chapter> out = new ArrayList<>();
        // 收集 zip 全部文本类条目
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.endsWith("/")) continue;
                String lower = name.toLowerCase();
                if (lower.endsWith(".opf") || lower.endsWith(".ncx") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")) {
                    byte[] bytes = readLimited(zip, MAX_ENTRY);
                    if (bytes != null) entries.put(name, bytes);
                }
            }
        } catch (Exception e) {
            return out;
        }
        // container.xml → OPF 路径
        byte[] container = entries.get("META-INF/container.xml");
        if (container == null) return out;
        String opfPath = findOpfPath(container);
        if (opfPath == null) return out;
        byte[] opf = findEntry(entries, opfPath);
        if (opf == null) return out;
        String opfDir = dirOf(opfPath);
        // 解析 manifest + spine
        Map<String, String> manifest = new LinkedHashMap<>(); // idref -> 归一化 entry 路径
        List<String> spine = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(new String(opf, StandardCharsets.UTF_8));
            for (Element it : doc.select("manifest item")) {
                String id = it.attr("id");
                String href = it.attr("href");
                String type = it.attr("media-type");
                if (id.isEmpty() || href.isEmpty()) continue;
                if (type != null && (type.contains("xhtml") || type.contains("html"))) {
                    manifest.put(id, normalize(opfDir, href));
                }
            }
            for (Element ir : doc.select("spine itemref")) {
                String idref = ir.attr("idref");
                if ("no".equalsIgnoreCase(ir.attr("linear"))) continue;
                if (manifest.containsKey(idref)) spine.add(manifest.get(idref));
            }
        } catch (Exception e) {
            return out;
        }
        int idx = 0;
        for (String path : spine) {
            byte[] bytes = findEntry(entries, path);
            if (bytes == null) continue;
            try {
                Document d = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
                d.select("script,style,nav,header,footer").remove();
                String title = firstText(d.select("h1,h2,h3"));
                if (title.isEmpty()) title = "第" + (idx + 1) + "节";
                StringBuilder sb = new StringBuilder();
                Elements ps = d.select("p");
                if (ps.isEmpty()) {
                    String t = d.body() == null ? "" : d.body().text();
                    if (!t.trim().isEmpty()) sb.append(t);
                } else {
                    for (Element p : ps) {
                        String line = p.text();
                        if (line.trim().isEmpty()) continue;
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(line);
                    }
                }
                if (sb.length() == 0) continue;
                out.add(new Chapter(title, sb.toString().trim()));
                idx++;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String findOpfPath(byte[] container) {
        try {
            Document doc = Jsoup.parse(new String(container, StandardCharsets.UTF_8));
            Element rootelement = doc.selectFirst("rootfile");
            return rootelement == null ? null : rootelement.attr("full-path");
        } catch (Exception e) {
            return null;
        }
    }

    /** 归一化：OPF 目录（可能为空）+ manifest href，规整掉 ./ 与 ../ 与前导斜杠。 */
    private static String normalize(String dir, String href) {
        String raw = dir.isEmpty() ? href : dir + "/" + href;
        String[] parts = raw.split("/");
        List<String> stack = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(p);
            }
        }
        return String.join("/", stack);
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : "";
    }

    /** 精确匹配优先；否则按结尾路径段匹配（处理 zip 内通配大小写差异）。 */
    private static byte[] findEntry(Map<String, byte[]> entries, String target) {
        byte[] hit = entries.get(target);
        if (hit != null) return hit;
        String tail = "/" + target;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String k = e.getKey().replace('\\', '/');
            if (k.endsWith(tail) || normalize("", k).equalsIgnoreCase(target)) return e.getValue();
        }
        return null;
    }

    private static String firstText(Elements els) {
        for (Element e : els) {
            String t = e.text();
            if (!t.trim().isEmpty()) return t.trim();
        }
        return "";
    }

    private static byte[] readLimited(InputStream is, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > max) return null;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private EpubImporter() {
    }
}