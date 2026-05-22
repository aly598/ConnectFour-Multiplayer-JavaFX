package com.example.demo.net;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight packet: type + key/value fields. Encoded as TYPE|k=v|k=v. */
public final class Packet {
    public final String type;
    public final Map<String,String> fields;

    public Packet(String type) { this(type, new LinkedHashMap<>()); }
    public Packet(String type, Map<String,String> fields) {
        this.type = type;
        this.fields = fields;
    }

    public Packet put(String k, String v) { fields.put(k, v == null ? "" : v); return this; }
    public String get(String k) { return fields.get(k); }
    public String get(String k, String def) { return fields.getOrDefault(k, def); }
    public int getInt(String k, int def) {
        String v = fields.get(k);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (Exception e) { return def; }
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(escape(type));
        for (var e : fields.entrySet()) {
            sb.append('|').append(escape(e.getKey())).append('=').append(escape(e.getValue()));
        }
        return sb.toString();
    }

    public static Packet decode(String line) {
        if (line == null || line.isEmpty()) return new Packet("");
        java.util.List<String> parts = splitEscaped(line, '|');
        Packet p = new Packet(unescape(parts.get(0)));
        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i);
            int eq = findUnescaped(part, '=');
            if (eq > 0) {
                p.fields.put(unescape(part.substring(0, eq)), unescape(part.substring(eq + 1)));
            }
        }
        return p;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("=", "\\=")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) {
                out.append(ch);
                esc = false;
            } else if (ch == '\\') {
                esc = true;
            } else {
                out.append(ch);
            }
        }
        if (esc) out.append('\\');
        return out.toString();
    }

    private static java.util.List<String> splitEscaped(String s, char delimiter) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) {
                cur.append('\\').append(ch);
                esc = false;
            } else if (ch == '\\') {
                esc = true;
            } else if (ch == delimiter) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (esc) cur.append('\\');
        out.add(cur.toString());
        return out;
    }

    private static int findUnescaped(String s, char needle) {
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) esc = false;
            else if (ch == '\\') esc = true;
            else if (ch == needle) return i;
        }
        return -1;
    }

    @Override public String toString() { return encode(); }
}
