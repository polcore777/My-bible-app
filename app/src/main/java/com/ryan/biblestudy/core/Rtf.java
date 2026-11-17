package com.ryan.biblestudy.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/** Converts the RTF used by e-Sword modules into simple, safe HTML. */
public final class Rtf {
    private Rtf() { }

    private static final Pattern RTF_HINT = Pattern.compile("\\{\\\\rtf|\\\\(par|cf\\d|b0?|i0?|super|plain|pard|line|f\\d|fs\\d|u-?\\d)[ \\\\{}\\r\\n\\d]");

    public static boolean looksLikeRtf(String s) {
        if (s == null) return false;
        String head = s.length() > 4000 ? s.substring(0, 4000) : s;
        return RTF_HINT.matcher(head).find();
    }

    private static final class State implements Cloneable {
        boolean b, i, u, sup, sub, hidden, skip;
        int color;      // index into color table, 0 = default
        int uc = 1;     // chars to skip after \\uN
        String dest;    // destination name
        @Override protected State clone() {
            try { return (State) super.clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
        }
    }

    public static String toHtml(String rtf) {
        return toHtml(rtf, 1252);
    }

    /** codepage decodes \\'xx byte escapes: 1252 default, 1253 for legacy Greek-font modules, 1255 Hebrew. */
    public static String toHtml(String rtf, int codepage) {
        if (rtf == null) return "";
        List<int[]> colors = new ArrayList<>();
        colors.add(null); // index 0 = auto
        StringBuilder out = new StringBuilder(rtf.length());
        Deque<State> stack = new ArrayDeque<>();
        State st = new State();
        State open = new State(); // formatting currently emitted
        int n = rtf.length();
        int skipChars = 0;
        int[] curColor = new int[]{-1, -1, -1};
        StringBuilder text = new StringBuilder();

        for (int p = 0; p < n; ) {
            char c = rtf.charAt(p);
            if (c == '{') {
                flush(text, out, st, open, colors);
                stack.push(st.clone());
                p++;
                // destination group "{\*\foo ...}" is ignorable
                if (p + 1 < n && rtf.charAt(p) == '\\' && rtf.charAt(p + 1) == '*') { st.skip = true; p += 2; }
            } else if (c == '}') {
                flush(text, out, st, open, colors);
                if (!stack.isEmpty()) st = stack.pop();
                p++;
            } else if (c == '\\') {
                p++;
                if (p >= n) break;
                char d = rtf.charAt(p);
                if (d == '\\' || d == '{' || d == '}') {
                    if (skipChars > 0) skipChars--; else if (!st.skip && !st.hidden) text.append(d);
                    p++;
                } else if (d == '\'') {
                    if (p + 2 < n + 0 && p + 2 <= n - 1 + 1) {
                        String hex = rtf.substring(p + 1, Math.min(n, p + 3));
                        p += 3;
                        try {
                            int v = Integer.parseInt(hex, 16);
                            if (skipChars > 0) skipChars--;
                            else if (!st.skip && !st.hidden) text.append(decodeByte(v, codepage));
                        } catch (NumberFormatException e) { /* ignore */ }
                    } else p++;
                } else if (d == '~') { if (!st.skip && !st.hidden) text.append(' '); p++; }
                else if (d == '-' || d == '_') { if (!st.skip && !st.hidden && d == '_') text.append('-'); p++; }
                else if (d == '\r' || d == '\n') { flush(text, out, st, open, colors); closeAll(out, open); if (!st.skip) out.append("<br>"); p++; }
                else if (Character.isLetter(d)) {
                    int s = p;
                    while (p < n && Character.isLetter(rtf.charAt(p))) p++;
                    String word = rtf.substring(s, p);
                    int numStart = p;
                    if (p < n && (rtf.charAt(p) == '-' || Character.isDigit(rtf.charAt(p)))) {
                        p++;
                        while (p < n && Character.isDigit(rtf.charAt(p))) p++;
                    }
                    String num = rtf.substring(numStart, p);
                    if (p < n && rtf.charAt(p) == ' ') p++;
                    int arg = 0;
                    boolean hasArg = !num.isEmpty() && !num.equals("-");
                    if (hasArg) { try { arg = Integer.parseInt(num); } catch (NumberFormatException e) { hasArg = false; } }

                    // colour table parsing
                    if ("colortbl".equals(st.dest)) {
                        if (word.equals("red")) curColor[0] = arg;
                        else if (word.equals("green")) curColor[1] = arg;
                        else if (word.equals("blue")) curColor[2] = arg;
                        continue;
                    }
                    if (word.equals("u")) {
                        int v = arg < 0 ? arg + 65536 : arg;
                        if (!st.skip && !st.hidden) text.append((char) v);
                        skipChars = st.uc;
                        continue;
                    }
                    if (st.skip) {
                        continue;
                    }
                    switch (word) {
                        case "fonttbl": case "stylesheet": case "info": case "pict": case "object":
                        case "header": case "footer": case "headerl": case "headerr": case "footerl": case "footerr":
                        case "listtable": case "listoverridetable": case "rsidtbl": case "generator": case "xmlnstbl":
                        case "themedata": case "colorschememapping": case "latentstyles": case "datastore": case "fldinst":
                        case "pntext": case "pntxta": case "pntxtb": case "bkmkstart": case "bkmkend": case "field_dummy":
                            flush(text, out, st, open, colors);
                            st.skip = true; break;
                        case "colortbl":
                            flush(text, out, st, open, colors);
                            st.dest = "colortbl"; break;
                        case "par": case "sect": case "page":
                            flush(text, out, st, open, colors); closeAll(out, open); out.append("<br>"); break;
                        case "line":
                            flush(text, out, st, open, colors); out.append("<br>"); break;
                        case "tab": text.append("&emsp;"); break;
                        case "emdash": text.append('—'); break;
                        case "endash": text.append('–'); break;
                        case "lquote": text.append('‘'); break;
                        case "rquote": text.append('’'); break;
                        case "ldblquote": text.append('“'); break;
                        case "rdblquote": text.append('”'); break;
                        case "bullet": text.append('•'); break;
                        case "uc": st.uc = hasArg ? arg : 1; break;
                        default:
                            flush(text, out, st, open, colors);
                            applyFormat(st, word, hasArg, arg);
                    }
                } else {
                    p++; // unknown control symbol
                }
            } else if (c == '\r' || c == '\n') {
                p++;
            } else {
                if ("colortbl".equals(st.dest)) {
                    if (c == ';') {
                        colors.add(curColor[0] < 0 ? null : new int[]{Math.max(0, curColor[0]), Math.max(0, curColor[1]), Math.max(0, curColor[2])});
                        curColor = new int[]{-1, -1, -1};
                    }
                    p++;
                    continue;
                }
                if (skipChars > 0) { skipChars--; p++; continue; }
                if (!st.skip && !st.hidden) text.append(c);
                p++;
            }
        }
        flush(text, out, st, open, colors);
        closeAll(out, open);
        return tidy(out.toString());
    }

    private static void applyFormat(State st, String w, boolean hasArg, int arg) {
        boolean on = !hasArg || arg != 0;
        switch (w) {
            case "b": st.b = on; break;
            case "i": st.i = on; break;
            case "ul": st.u = on; break;
            case "ulnone": st.u = false; break;
            case "super": st.sup = true; st.sub = false; break;
            case "sub": st.sub = true; st.sup = false; break;
            case "nosupersub": st.sup = false; st.sub = false; break;
            case "up": st.sup = on; break;
            case "dn": st.sub = on; break;
            case "v": st.hidden = on; break;
            case "cf": st.color = hasArg ? arg : 0; break;
            case "plain": st.b = st.i = st.u = st.sup = st.sub = st.hidden = false; st.color = 0; break;
            default: break;
        }
    }

    private static String colorCss(int idx, List<int[]> colors) {
        if (idx <= 0 || idx >= colors.size()) return null;
        int[] c = colors.get(idx);
        if (c == null) return null;
        // black / near-black / white are "default text" - let the theme decide
        int max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        if (max < 70 || min > 200) return null;
        if (c[0] > 150 && c[1] < 90 && c[2] < 90) return "red";
        if (c[2] > 150 && c[0] < 100) return "blue";
        if (c[1] > 90 && c[0] < 90 && c[2] < 90) return "green";
        if (c[0] > 120 && c[2] > 120 && c[1] < 90) return "purple";
        if (c[0] > 150 && c[1] > 80 && c[2] < 60) return "orange";
        if (Math.abs(c[0] - c[1]) < 25 && Math.abs(c[1] - c[2]) < 25) return "gray";
        return "accent";
    }

    private static void flush(StringBuilder text, StringBuilder out, State st, State open, List<int[]> colors) {
        if (text.length() == 0) return;
        // close spans that differ, then open the new formatting
        boolean same = open.b == st.b && open.i == st.i && open.u == st.u && open.sup == st.sup
                && open.sub == st.sub && open.color == st.color;
        if (!same) {
            closeAll(out, open);
            String col = colorCss(st.color, colors);
            if (col != null) { out.append("<span class=\"c-").append(col).append("\">"); }
            if (st.b) out.append("<b>");
            if (st.i) out.append("<i>");
            if (st.u) out.append("<u>");
            if (st.sup) out.append("<sup>");
            if (st.sub) out.append("<sub>");
            open.b = st.b; open.i = st.i; open.u = st.u; open.sup = st.sup; open.sub = st.sub;
            open.color = col != null ? st.color : 0;
            open.dest = col; // remember whether a colour span is open
        }
        out.append(Html.escape(text.toString()));
        text.setLength(0);
    }

    private static void closeAll(StringBuilder out, State open) {
        if (open.sub) out.append("</sub>");
        if (open.sup) out.append("</sup>");
        if (open.u) out.append("</u>");
        if (open.i) out.append("</i>");
        if (open.b) out.append("</b>");
        if (open.dest != null) out.append("</span>");
        open.b = open.i = open.u = open.sup = open.sub = false;
        open.color = 0;
        open.dest = null;
    }

    private static String tidy(String html) {
        String s = html.replaceAll("(<br>\\s*){3,}", "<br><br>");
        s = s.replaceAll("^(\\s*<br>)+", "").replaceAll("(<br>\\s*)+$", "");
        return s.trim();
    }

    private static final char[] CP1252_80 = {
            '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
            '\u0090', '‘', '’', '“', '”', '•', '–', '—', '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ'};

    static char cp1252(int v) {
        if (v >= 0x80 && v <= 0x9F) return CP1252_80[v - 0x80];
        return (char) v;
    }

    private static java.nio.charset.Charset gk, hb;

    /** Decodes one byte from an RTF \\'xx escape using the module's code page. */
    static String decodeByte(int v, int codepage) {
        if (v < 0x80) return String.valueOf((char) v);
        try {
            if (codepage == 1253) {
                if (gk == null) gk = java.nio.charset.Charset.forName("windows-1253");
                return new String(new byte[]{(byte) v}, gk);
            }
            if (codepage == 1255) {
                if (hb == null) hb = java.nio.charset.Charset.forName("windows-1255");
                return new String(new byte[]{(byte) v}, hb);
            }
        } catch (Exception ignored) { }
        return String.valueOf(cp1252(v));
    }
}
