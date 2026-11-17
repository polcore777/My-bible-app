package com.ryan.biblestudy.core;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Html {
    private Html() { }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Plain text rendered as HTML (line breaks kept). */
    public static String fromPlain(String s) {
        if (s == null) return "";
        return escape(s.replace("\r\n", "\n").replace('\r', '\n')).replace("\n", "<br>");
    }

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|iframe|object|embed)[^>]*>.*?</\\1\\s*>");
    private static final Pattern SCRIPT_OPEN = Pattern.compile("(?is)<(script|style|iframe|object|embed|link|meta)[^>]*>");
    private static final Pattern EVENT_ATTR = Pattern.compile("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URL = Pattern.compile("(?i)(href|src)\\s*=\\s*([\"']?)\\s*javascript:");

    /** Removes scripts and event handlers from module HTML (modules are third-party content). */
    public static String sanitize(String html) {
        if (html == null) return "";
        String s = SCRIPT.matcher(html).replaceAll("");
        s = SCRIPT_OPEN.matcher(s).replaceAll("");
        s = EVENT_ATTR.matcher(s).replaceAll("");
        s = JS_URL.matcher(s).replaceAll("$1=$2#");
        return s;
    }

    private static final Pattern STRONG_SUP = Pattern.compile("(?is)<sup class=\"s\">.*?</sup>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Lower-case searchable text: tags and Strong's numbers removed. */
    public static String toPlain(String html) {
        if (html == null) return "";
        String s = STRONG_SUP.matcher(html).replaceAll(" ");
        s = s.replaceAll("(?i)<br\\s*/?>", " ");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&emsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        s = s.toLowerCase(Locale.ROOT);
        s = NON_WORD.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    /** Normalises a Strong's key: "H07225" / "h 7225" / "07225" (with hint) -> "H7225". */
    public static String strongKey(String key) {
        if (key == null) return null;
        String k = key.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (k.matches("[HG]0*\\d{1,5}[A-Z]?")) {
            char p = k.charAt(0);
            String rest = k.substring(1);
            String digits = rest.replaceAll("[A-Z]$", "");
            String suffix = rest.substring(digits.length());
            return p + String.valueOf(Integer.parseInt(digits)) + suffix;
        }
        return null;
    }
}
