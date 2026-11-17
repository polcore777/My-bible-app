package com.ryan.biblestudy.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects what kind of module a file is (by extension and by its tables/columns) and
 * streams its content into a {@link ModuleSink}. Works for legacy e-Sword (Jet), e-Sword 9+
 * (SQLite, RTF or HTML) and MySword (SQLite) modules.
 */
public final class Converter {
    private Converter() { }

    public static final class Result {
        public String type;
        public String title;
        public String abbreviation;
        public int count;
    }

    public static Result convert(SourceDb src, String fileName, ModuleSink sink) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean mysword = lower.endsWith(".mybible");
        String hint = typeFromExtension(lower);

        Map<String, String> info = readDetails(src, fileName);
        Result res = new Result();
        res.title = info.get("title");
        res.abbreviation = info.get("abbreviation");

        // Find the tables we know how to read
        String bibleT = null, verseT = null, chapterT = null, bookT = null, mysCmtT = null,
                dictT = null, sectionT = null, imageT = null, devT = null;
        for (String t : src.tableNames()) {
            if (t.equalsIgnoreCase("Details") || t.toLowerCase(Locale.ROOT).startsWith("msys")) continue;
            List<String> cols = lowerCols(src.columnNames(t));
            if (has(cols, "book", "chapter", "verse", "scripture")) bibleT = t;
            else if (has(cols, "book", "chapterbegin", "versebegin")) verseT = t;
            else if (has(cols, "book", "chapter", "fromverse")) mysCmtT = t;
            else if (has(cols, "book", "chapter") && (cols.contains("comments") || cols.contains("notes"))) chapterT = t;
            else if (has(cols, "book") && (cols.contains("comments") || cols.contains("notes")) && !cols.contains("chapter")) bookT = t;
            else if (has(cols, "topic", "definition") || has(cols, "word", "data") || has(cols, "word", "definition")) dictT = t;
            else if ((has(cols, "month", "day") || has(cols, "day", "devotion")) && devT == null) devT = t;
            else if (imageColumn(cols) != null && titleColumn(cols) != null) imageT = t;
            else if (titleColumn(cols) != null && bodyColumn(cols) != null) { if (sectionT == null) sectionT = t; }
        }

        String type;
        if (bibleT != null && (hint == null || hint.equals(ModuleSink.BIBLE))) type = ModuleSink.BIBLE;
        else if ((verseT != null || chapterT != null || bookT != null || mysCmtT != null)
                && (hint == null || hint.equals(ModuleSink.COMMENTARY))) type = ModuleSink.COMMENTARY;
        else if (dictT != null && (hint == null || hint.equals(ModuleSink.DICTIONARY))) type = ModuleSink.DICTIONARY;
        else if (imageT != null && (hint == null || hint.equals(ModuleSink.IMAGES))) type = ModuleSink.IMAGES;
        else if (sectionT != null || devT != null) type = ModuleSink.BOOK;
        else if (bibleT != null) type = ModuleSink.BIBLE;
        else if (verseT != null || chapterT != null || bookT != null || mysCmtT != null) type = ModuleSink.COMMENTARY;
        else if (dictT != null) type = ModuleSink.DICTIONARY;
        else if (imageT != null) type = ModuleSink.IMAGES;
        else throw new IOException("This file doesn't contain anything this app recognises.");

        res.type = type;
        info.put("type", type);
        info.put("source", fileName);
        sink.begin(type);
        final int[] count = {0};
        final boolean[] strongs = {false};

        switch (type) {
            case ModuleSink.BIBLE:
                src.scan(bibleT, row -> {
                    int b = num(row.get("Book")), c = num(row.get("Chapter")), v = num(row.get("Verse"));
                    if (b <= 0 || c <= 0 || v < 0) return true;
                    String html = bibleHtml(str(row.get("Scripture")), b, mysword);
                    if (html.contains("class=\"s\"")) strongs[0] = true;
                    sink.verse(b, c, v, html, Html.toPlain(html));
                    count[0]++;
                    return true;
                });
                break;

            case ModuleSink.COMMENTARY:
                if (bookT != null) src.scan(bookT, row -> {
                    sink.comment(num(row.get("Book")), 0, 0, 0, 0, body(row, mysword));
                    count[0]++;
                    return true;
                });
                if (chapterT != null) src.scan(chapterT, row -> {
                    int ch = num(row.get("Chapter"));
                    sink.comment(num(row.get("Book")), ch, 0, ch, 0, body(row, mysword));
                    count[0]++;
                    return true;
                });
                if (verseT != null) src.scan(verseT, row -> {
                    int b = num(row.get("Book"));
                    int c1 = num(row.get("ChapterBegin")), v1 = num(row.get("VerseBegin"));
                    int c2 = num(row.get("ChapterEnd")), v2 = num(row.get("VerseEnd"));
                    if (c2 <= 0) c2 = c1;
                    if (v2 <= 0) v2 = v1;
                    sink.comment(b, c1, v1, c2, v2, body(row, mysword));
                    count[0]++;
                    return true;
                });
                if (mysCmtT != null) src.scan(mysCmtT, row -> {
                    int b = num(row.get("book")), c = num(row.get("chapter"));
                    int v1 = num(row.get("fromverse")), v2 = num(row.get("toverse"));
                    if (v2 <= 0) v2 = v1;
                    sink.comment(b, c, v1, c, v2, body(row, mysword));
                    count[0]++;
                    return true;
                });
                break;

            case ModuleSink.DICTIONARY: {
                final int[] strongKeys = {0};
                src.scan(dictT, row -> {
                    String key = str(row.get("Topic"));
                    if (key == null) key = str(row.get("word"));
                    if (key == null) return true;
                    key = key.trim();
                    String sk = Html.strongKey(key);
                    if (sk != null) { key = sk; strongKeys[0]++; }
                    String raw = str(row.get("Definition"));
                    if (raw == null) raw = str(row.get("data"));
                    String html = content(raw, false);
                    sink.entry(key, html, Html.toPlain(html));
                    count[0]++;
                    return true;
                });
                strongs[0] = count[0] > 0 && strongKeys[0] * 2 > count[0];
                break;
            }

            case ModuleSink.IMAGES: {
                final List<String> cols = lowerCols(src.columnNames(imageT));
                final String imgCol = imageColumn(cols), titleCol = titleColumn(cols);
                src.scan(imageT, row -> {
                    Object data = row.get(imgCol);
                    if (!(data instanceof byte[])) return true;
                    Object[] img = OleImage.extract((byte[]) data);
                    if (img == null) return true;
                    String title = str(row.get(titleCol));
                    sink.image(count[0], title == null ? ("Image " + (count[0] + 1)) : title.trim(), (String) img[0], (byte[]) img[1]);
                    count[0]++;
                    return true;
                });
                break;
            }

            default: { // BOOK
                if (sectionT != null) {
                    final List<String> cols = lowerCols(src.columnNames(sectionT));
                    final String titleCol = titleColumn(cols), bodyCol = bodyColumn(cols);
                    final List<Object[]> rows = new ArrayList<>();
                    src.scan(sectionT, row -> {
                        Object id = row.get("ID");
                        if (id == null) id = row.get("id");
                        rows.add(new Object[]{id instanceof Number ? ((Number) id).intValue() : rows.size(),
                                str(row.get(titleCol)), str(row.get(bodyCol))});
                        return true;
                    });
                    rows.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
                    for (Object[] r : rows) {
                        String html = content((String) r[2], false);
                        String title = r[1] == null ? "" : ((String) r[1]).trim();
                        sink.section(count[0], title, html, Html.toPlain(html));
                        count[0]++;
                    }
                } else {
                    final String[] months = {"January", "February", "March", "April", "May", "June", "July",
                            "August", "September", "October", "November", "December"};
                    src.scan(devT, row -> {
                        int m = num(row.get("Month")), d = num(row.get("Day"));
                        String raw = str(row.get("Devotion"));
                        if (raw == null) raw = str(row.get("Notes"));
                        if (raw == null) raw = str(row.get("Comments"));
                        String title = (m >= 1 && m <= 12) ? months[m - 1] + " " + d : ("Day " + d);
                        String html = content(raw, false);
                        sink.section(m > 0 ? m * 100 + d : d, title, html, Html.toPlain(html));
                        count[0]++;
                        return true;
                    });
                }
            }
        }
        if (strongs[0]) info.put("strongs", "1");
        res.count = count[0];
        sink.end(info);
        return res;
    }

    // ------------------------------------------------------------------ details

    static Map<String, String> readDetails(SourceDb src, String fileName) throws IOException {
        final Map<String, String> info = new HashMap<>();
        String details = null;
        for (String t : src.tableNames()) if (t.equalsIgnoreCase("Details")) details = t;
        if (details != null) {
            final List<String> cols = src.columnNames(details);
            src.scan(details, row -> {
                for (String c : cols) {
                    Object v = row.get(c);
                    if (v != null && !(v instanceof byte[])) info.put(c.toLowerCase(Locale.ROOT), v.toString().trim());
                }
                return false; // first row only
            });
        }
        Map<String, String> out = new HashMap<>();
        String base = baseName(fileName);
        String title = first(info, "title", "description");
        String abbr = first(info, "abbreviation", "abbr");
        if (title != null && Rtf.looksLikeRtf(title)) title = Html.toPlain(Rtf.toHtml(title));
        out.put("title", title == null || title.isEmpty() ? base : title);
        out.put("abbreviation", abbr == null || abbr.isEmpty() ? base.toUpperCase(Locale.ROOT) : abbr);
        String desc = first(info, "comments", "description");
        if (desc != null) out.put("description", content(desc, false));
        String rtl = info.get("righttoleft");
        if (rtl != null && (rtl.equals("1") || rtl.equalsIgnoreCase("true"))) out.put("rtl", "1");
        String ver = info.get("version");
        if (ver != null) out.put("version", ver);
        return out;
    }

    public static String baseName(String fileName) {
        String n = fileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".mybible")) n = n.substring(0, n.length() - 8);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        // drop the random prefix some apps add, e.g. "1b6e77c4-"
        n = n.replaceFirst("^[0-9a-f]{8}-", "");
        return n.replace('_', ' ').trim();
    }

    public static String typeFromExtension(String lower) {
        String l = lower.endsWith(".mybible") ? lower.substring(0, lower.length() - 8) : lower;
        int dot = l.lastIndexOf('.');
        String ext = dot >= 0 ? l.substring(dot + 1) : "";
        if (ext.startsWith("bbl")) return ModuleSink.BIBLE;
        if (ext.startsWith("cmt") || ext.startsWith("not") || ext.equals("ann")) return ModuleSink.COMMENTARY;
        if (ext.startsWith("dct") || ext.startsWith("lex")) return ModuleSink.DICTIONARY;
        if (ext.startsWith("map") || ext.startsWith("gfx")) return ModuleSink.IMAGES;
        if (ext.startsWith("top") || ext.startsWith("ref") || ext.startsWith("dev") || ext.startsWith("bok")
                || ext.startsWith("brp") || ext.startsWith("jor")) return ModuleSink.BOOK;
        return null;
    }

    public static boolean isModuleFile(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.endsWith(".mybible")) return true;
        int dot = l.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = l.substring(dot + 1);
        return ext.matches("(bbl|cmt|dct|lex|top|ref|dev|map|brp|not)[xi]?");
    }

    // -------------------------------------------------------------- formatting

    private static final Pattern SUP_NUM = Pattern.compile("<sup>\\s*([HGhg]?)\\s*0*(\\d{1,5})\\s*</sup>");
    private static final Pattern NUM_TAG = Pattern.compile("<num>\\s*([HGhg]?)0*(\\d{1,5})\\s*</num>");
    private static final Pattern BRACKET_NUM = Pattern.compile("\\[([HG])0*(\\d{1,5})]");

    static String bibleHtml(String raw, int book, boolean mysword) {
        String html = content(raw, mysword || Gbf.looksLikeGbf(raw));
        String def = book <= 39 ? "H" : "G";
        html = strongify(SUP_NUM, html, def);
        html = strongify(NUM_TAG, html, def);
        html = strongify(BRACKET_NUM, html, def);
        return html;
    }

    private static String strongify(Pattern p, String html, String def) {
        if (html.indexOf('<') < 0 && html.indexOf('[') < 0) return html;
        Matcher m = p.matcher(html);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            any = true;
            String prefix = m.group(1);
            prefix = prefix == null || prefix.isEmpty() ? def : prefix.toUpperCase(Locale.ROOT);
            m.appendReplacement(sb, Matcher.quoteReplacement("<sup class=\"s\">" + prefix + Integer.parseInt(m.group(2)) + "</sup>"));
        }
        if (!any) return html;
        m.appendTail(sb);
        return sb.toString();
    }

    static String body(SourceDb.Row row, boolean mysword) {
        String raw = str(row.get("Comments"));
        if (raw == null) raw = str(row.get("Notes"));
        if (raw == null) raw = str(row.get("data"));
        return content(raw, false);
    }

    private static final Pattern HTML_TAG = Pattern.compile("<(p|br|b|i|u|span|div|font|sup|sub|a|h\\d|table|tr|td|ul|ol|li|em|strong|img|hr|blockquote)\\b", Pattern.CASE_INSENSITIVE);

    static String content(String raw, boolean gbf) {
        if (raw == null) return "";
        if (Rtf.looksLikeRtf(raw)) return Rtf.toHtml(raw);
        if (gbf && Gbf.looksLikeGbf(raw)) return Html.sanitize(Gbf.toHtml(raw));
        if (HTML_TAG.matcher(raw).find()) return Html.sanitize(raw);
        return Html.fromPlain(raw);
    }

    // ----------------------------------------------------------------- helpers

    static List<String> lowerCols(List<String> cols) {
        List<String> out = new ArrayList<>();
        for (String c : cols) out.add(c.toLowerCase(Locale.ROOT));
        return out;
    }

    static boolean has(List<String> cols, String... names) {
        for (String n : names) if (!cols.contains(n)) return false;
        return true;
    }

    static String titleColumn(List<String> cols) {
        for (String c : new String[]{"title", "topic", "name", "caption", "word"}) if (cols.contains(c)) return c;
        return null;
    }

    static String bodyColumn(List<String> cols) {
        for (String c : new String[]{"comments", "notes", "data", "text", "content", "definition", "body"}) if (cols.contains(c)) return c;
        return null;
    }

    static String imageColumn(List<String> cols) {
        for (String c : new String[]{"image", "map", "picture", "graphic", "bitmap", "photo", "img"}) if (cols.contains(c)) return c;
        return null;
    }

    static String first(Map<String, String> m, String... keys) {
        for (String k : keys) { String v = m.get(k); if (v != null && !v.isEmpty()) return v; }
        return null;
    }

    static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) { try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }

    static String str(Object o) {
        if (o == null || o instanceof byte[]) return null;
        return o.toString();
    }
}
