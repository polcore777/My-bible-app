package com.ryan.biblestudy.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the GBF-style tags used in MySword Bible text into HTML. */
public final class Gbf {
    private Gbf() { }

    private static final Pattern TAG = Pattern.compile("<(W[HG])(\\d+[a-z]?)>|<WT[^>]*>|<(FI|Fi|FR|Fr|FU|Fu|FB|Fb|FO|Fo|FS|Fs|CM|CL|CI|PF\\d|PI\\d?|RF|Rf|RX[^>]*|TS|Ts|Q[^>]*|q|H|h|J|j|E|e|T|t|N[^>]*|n|S[^>]*|s)>");

    public static boolean looksLikeGbf(String s) {
        return s != null && (s.contains("<WH") || s.contains("<WG") || s.contains("<FI>") || s.contains("<CM>")
                || s.contains("<RF>") || s.contains("<FR>") || s.contains("<TS>"));
    }

    public static String toHtml(String s) {
        if (s == null) return "";
        StringBuffer out = new StringBuffer(s.length() + 32);
        Matcher m = TAG.matcher(s);
        while (m.find()) {
            String rep;
            if (m.group(1) != null) {
                rep = "<sup class=\"s\">" + m.group(1).charAt(1) + m.group(2) + "</sup>";
            } else {
                String t = m.group(3);
                if (t == null) { rep = ""; }
                else switch (t) {
                    case "FI": rep = "<i>"; break;
                    case "Fi": rep = "</i>"; break;
                    case "FR": rep = "<span class=\"wj\">"; break;
                    case "Fr": rep = "</span>"; break;
                    case "FU": rep = "<u>"; break;
                    case "Fu": rep = "</u>"; break;
                    case "FB": rep = "<b>"; break;
                    case "Fb": rep = "</b>"; break;
                    case "FO": rep = "<span class=\"ot\">"; break;
                    case "Fo": rep = "</span>"; break;
                    case "FS": rep = "<sup>"; break;
                    case "Fs": rep = "</sup>"; break;
                    case "CM": rep = "<br>"; break;
                    case "CL": rep = "<br>"; break;
                    case "RF": rep = "<span class=\"fn\">"; break;
                    case "Rf": rep = "</span>"; break;
                    case "TS": rep = "<span class=\"ttl\">"; break;
                    case "Ts": rep = "</span>"; break;
                    default: rep = "";
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString().replaceAll("(<br>\\s*)+$", "");
    }
}
