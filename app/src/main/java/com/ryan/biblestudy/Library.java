package com.ryan.biblestudy;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import com.ryan.biblestudy.core.Html;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The app's library: installed modules (one SQLite file each) plus the user's own notes. */
public final class Library {
    private final Context ctx;
    final File dir;
    private final Map<String, SQLiteDatabase> open = new HashMap<>();
    private final SQLiteDatabase user;

    public Library(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "modules");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        installBuiltins();
        user = SQLiteDatabase.openOrCreateDatabase(new File(ctx.getFilesDir(), "user.db"), null);
        user.execSQL("CREATE TABLE IF NOT EXISTS notes(book INTEGER, chapter INTEGER, verse INTEGER, text TEXT, updated INTEGER, PRIMARY KEY(book, chapter, verse))");
        user.execSQL("CREATE TABLE IF NOT EXISTS highlights(book INTEGER, chapter INTEGER, verse INTEGER, color TEXT, updated INTEGER, PRIMARY KEY(book, chapter, verse))");
        user.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(book INTEGER, chapter INTEGER, verse INTEGER, updated INTEGER, PRIMARY KEY(book, chapter, verse))");
        user.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)");
        user.execSQL("CREATE TABLE IF NOT EXISTS plan(plan TEXT, day INTEGER, PRIMARY KEY(plan, day))");
    }

    // --------------------------------------------------------------- builtins

    /** Copies the built-in modules out of the APK on first run and after every app update. */
    private void installBuiltins() {
        long stamp;
        try { stamp = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime; }
        catch (Exception e) { stamp = 0; }
        SharedPreferences prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE);
        boolean fresh = prefs.getLong("builtins", -1) != stamp;
        try {
            String[] names = ctx.getAssets().list("modules");
            if (names == null) return;
            for (String n : names) {
                if (!n.endsWith(".db")) continue;
                File out = new File(dir, n);
                if (out.exists() && !fresh) continue;
                File tmp = new File(dir, n + ".tmp");
                try (InputStream in = ctx.getAssets().open("modules/" + n); OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                }
                closeModule(n.substring(0, n.length() - 3));
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(out);
            }
            prefs.edit().putLong("builtins", stamp).apply();
        } catch (IOException e) {
            android.util.Log.e("Library", "builtin install failed", e);
        }
    }

    // ---------------------------------------------------------------- modules

    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9._-]{1,120}") && !id.contains(".."); }

    synchronized SQLiteDatabase db(String id) {
        if (!validId(id)) return null;
        SQLiteDatabase d = open.get(id);
        if (d != null && d.isOpen()) return d;
        File f = new File(dir, id + ".db");
        if (!f.exists()) return null;
        d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        open.put(id, d);
        return d;
    }

    synchronized void closeModule(String id) {
        SQLiteDatabase d = open.remove(id);
        if (d != null) d.close();
    }

    public synchronized String modules() throws JSONException {
        JSONArray arr = new JSONArray();
        File[] files = dir.listFiles();
        if (files == null) return arr.toString();
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".db")) continue;
            String id = n.substring(0, n.length() - 3);
            try {
                SQLiteDatabase d = db(id);
                if (d == null) continue;
                Map<String, String> info = new HashMap<>();
                try (Cursor c = d.rawQuery("SELECT key, value FROM info", null)) {
                    while (c.moveToNext()) info.put(c.getString(0), c.getString(1));
                }
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("type", info.get("type"));
                o.put("title", info.containsKey("title") ? info.get("title") : id);
                o.put("abbr", info.containsKey("abbreviation") ? info.get("abbreviation") : id);
                o.put("strongs", "1".equals(info.get("strongs")));
                o.put("builtin", "1".equals(info.get("builtin")));
                o.put("rtl", "1".equals(info.get("rtl")));
                arr.put(o);
            } catch (Exception e) {
                android.util.Log.w("Library", "bad module " + n, e);
            }
        }
        return arr.toString();
    }

    public boolean deleteModule(String id) {
        if (!validId(id)) return false;
        closeModule(id);
        return new File(dir, id + ".db").delete();
    }

    // ------------------------------------------------------------------ bible

    public String chapter(String id, int b, int c) {
        JSONArray arr = new JSONArray();
        SQLiteDatabase d = db(id);
        if (d == null) return arr.toString();
        try (Cursor cur = d.rawQuery("SELECT verse, html FROM verses WHERE book=? AND chapter=? ORDER BY verse", args(b, c))) {
            while (cur.moveToNext()) arr.put(new JSONArray().put(cur.getInt(0)).put(cur.getString(1)));
        }
        return arr.toString();
    }

    public String verseText(String id, int b, int c, int v) {
        SQLiteDatabase d = db(id);
        if (d == null) return JSONObject.quote("");
        try (Cursor cur = d.rawQuery("SELECT html FROM verses WHERE book=? AND chapter=? AND verse=?", args(b, c, v))) {
            return JSONObject.quote(cur.moveToFirst() ? cur.getString(0) : "");
        }
    }

    public String search(String id, String query, String scope, boolean whole, int limit) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray res = new JSONArray();
        out.put("total", 0);
        out.put("results", res);
        SQLiteDatabase d = db(id);
        if (d == null || query == null || query.trim().isEmpty()) return out.toString();
        StringBuilder where = new StringBuilder();
        List<String> a = new ArrayList<>();
        String q = query.trim();
        String strong = Html.strongKey(q);
        if (strong != null) {
            where.append("html LIKE ?");
            a.add("%>" + strong + "<%");
        } else {
            Matcher m = Pattern.compile("\"([^\"]+)\"|(\\S+)").matcher(q);
            while (m.find()) {
                String tok = Html.toPlain(Html.escape(m.group(1) != null ? m.group(1) : m.group(2)));
                if (tok.isEmpty()) continue;
                if (where.length() > 0) where.append(" AND ");
                String like = tok.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                if (whole) { where.append("(' ' || plain || ' ') LIKE ? ESCAPE '\\'"); a.add("% " + like + " %"); }
                else { where.append("plain LIKE ? ESCAPE '\\'"); a.add("%" + like + "%"); }
            }
            if (where.length() == 0) return out.toString();
        }
        if ("ot".equals(scope)) where.append(" AND book<=39");
        else if ("nt".equals(scope)) where.append(" AND book>=40");
        else if (scope != null && scope.startsWith("book:")) { where.append(" AND book=?"); a.add(scope.substring(5)); }
        String[] argv = a.toArray(new String[0]);
        try (Cursor cur = d.rawQuery("SELECT COUNT(*) FROM verses WHERE " + where, argv)) {
            if (cur.moveToFirst()) out.put("total", cur.getInt(0));
        }
        try (Cursor cur = d.rawQuery("SELECT book, chapter, verse, html FROM verses WHERE " + where + " ORDER BY book, chapter, verse LIMIT " + Math.max(1, Math.min(limit, 2000)), argv)) {
            while (cur.moveToNext())
                res.put(new JSONArray().put(cur.getInt(0)).put(cur.getInt(1)).put(cur.getInt(2)).put(cur.getString(3)));
        }
        return out.toString();
    }

    // ------------------------------------------------------------- commentary

    public String comments(String id, int b, int c, int v) throws JSONException {
        JSONArray arr = new JSONArray();
        SQLiteDatabase d = db(id);
        if (d == null) return arr.toString();
        String sql = "SELECT ch1, v1, ch2, v2, html FROM comments WHERE book=? AND ("
                + "ch1=0 OR (v1=0 AND ch1<=? AND ch2>=?) OR "
                + "(v1>0 AND (ch1<? OR (ch1=? AND v1<=?)) AND (ch2>? OR (ch2=? AND v2>=?)))) ORDER BY ch1, v1, ch2, v2";
        try (Cursor cur = d.rawQuery(sql, args(b, c, c, c, c, v, c, c, v))) {
            while (cur.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ch1", cur.getInt(0)); o.put("v1", cur.getInt(1));
                o.put("ch2", cur.getInt(2)); o.put("v2", cur.getInt(3));
                o.put("html", cur.getString(4));
                arr.put(o);
            }
        }
        return arr.toString();
    }

    // ------------------------------------------------------------- dictionary

    public String entry(String id, String key) {
        SQLiteDatabase d = db(id);
        if (d == null || key == null) return JSONObject.quote("");
        String k = key.trim();
        String sk = Html.strongKey(k);
        if (sk != null) k = sk;
        try (Cursor cur = d.rawQuery("SELECT html FROM entries WHERE key=? COLLATE NOCASE LIMIT 1", new String[]{k})) {
            return JSONObject.quote(cur.moveToFirst() ? cur.getString(0) : "");
        }
    }

    public String entryKeys(String id, String prefix, int limit) {
        JSONArray arr = new JSONArray();
        SQLiteDatabase d = db(id);
        if (d == null || prefix == null) return arr.toString();
        String p = prefix.trim();
        String sk = Html.strongKey(p);
        if (sk != null) p = sk;
        String like = p.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int lim = Math.max(1, Math.min(limit, 200));
        try (Cursor cur = d.rawQuery("SELECT key FROM entries WHERE key LIKE ? ESCAPE '\\' ORDER BY length(key), key COLLATE NOCASE LIMIT " + lim, new String[]{like + "%"})) {
            while (cur.moveToNext()) arr.put(cur.getString(0));
        }
        if (arr.length() < lim) {
            try (Cursor cur = d.rawQuery("SELECT key FROM entries WHERE key LIKE ? ESCAPE '\\' AND key NOT LIKE ? ESCAPE '\\' ORDER BY key COLLATE NOCASE LIMIT " + (lim - arr.length()),
                    new String[]{"%" + like + "%", like + "%"})) {
                while (cur.moveToNext()) arr.put(cur.getString(0));
            }
        }
        return arr.toString();
    }

    // ------------------------------------------------------------ books/images

    public String sections(String id) throws JSONException {
        JSONArray arr = new JSONArray();
        SQLiteDatabase d = db(id);
        if (d == null) return arr.toString();
        try (Cursor cur = d.rawQuery("SELECT id, title FROM sections ORDER BY id", null)) {
            while (cur.moveToNext()) arr.put(new JSONObject().put("id", cur.getInt(0)).put("title", cur.getString(1)));
        }
        return arr.toString();
    }

    public String section(String id, int sid) throws JSONException {
        SQLiteDatabase d = db(id);
        JSONObject o = new JSONObject();
        if (d == null) return o.toString();
        try (Cursor cur = d.rawQuery("SELECT id, title, html FROM sections WHERE id=?", args(sid))) {
            if (cur.moveToFirst()) o.put("id", cur.getInt(0)).put("title", cur.getString(1)).put("html", cur.getString(2));
        }
        return o.toString();
    }

    public String images(String id) throws JSONException {
        JSONArray arr = new JSONArray();
        SQLiteDatabase d = db(id);
        if (d == null) return arr.toString();
        try (Cursor cur = d.rawQuery("SELECT id, title FROM images ORDER BY id", null)) {
            while (cur.moveToNext()) arr.put(new JSONObject().put("id", cur.getInt(0)).put("title", cur.getString(1)));
        }
        return arr.toString();
    }

    public String image(String id, int iid) {
        SQLiteDatabase d = db(id);
        if (d == null) return JSONObject.quote("");
        try (Cursor cur = d.rawQuery("SELECT mime, data FROM images WHERE id=?", args(iid))) {
            if (!cur.moveToFirst()) return JSONObject.quote("");
            return JSONObject.quote("data:" + cur.getString(0) + ";base64," + Base64.encodeToString(cur.getBlob(1), Base64.NO_WRAP));
        }
    }

    // ------------------------------------------------------------- user data

    public synchronized String userChapter(int b, int c) throws JSONException {
        JSONObject notes = new JSONObject(), hl = new JSONObject();
        JSONArray bm = new JSONArray();
        try (Cursor cur = user.rawQuery("SELECT verse, text FROM notes WHERE book=? AND chapter=?", args(b, c))) {
            while (cur.moveToNext()) notes.put(String.valueOf(cur.getInt(0)), cur.getString(1));
        }
        try (Cursor cur = user.rawQuery("SELECT verse, color FROM highlights WHERE book=? AND chapter=?", args(b, c))) {
            while (cur.moveToNext()) hl.put(String.valueOf(cur.getInt(0)), cur.getString(1));
        }
        try (Cursor cur = user.rawQuery("SELECT verse FROM bookmarks WHERE book=? AND chapter=?", args(b, c))) {
            while (cur.moveToNext()) bm.put(cur.getInt(0));
        }
        return new JSONObject().put("notes", notes).put("hl", hl).put("bm", bm).toString();
    }

    public synchronized void setNote(int b, int c, int v, String text) {
        if (text == null || text.trim().isEmpty()) user.execSQL("DELETE FROM notes WHERE book=? AND chapter=? AND verse=?", new Object[]{b, c, v});
        else user.execSQL("INSERT OR REPLACE INTO notes VALUES(?,?,?,?,?)", new Object[]{b, c, v, text, System.currentTimeMillis()});
    }

    public synchronized void setHighlight(int b, int c, int v, String color) {
        if (color == null || color.isEmpty()) user.execSQL("DELETE FROM highlights WHERE book=? AND chapter=? AND verse=?", new Object[]{b, c, v});
        else user.execSQL("INSERT OR REPLACE INTO highlights VALUES(?,?,?,?,?)", new Object[]{b, c, v, color, System.currentTimeMillis()});
    }

    public synchronized boolean toggleBookmark(int b, int c, int v) {
        try (Cursor cur = user.rawQuery("SELECT 1 FROM bookmarks WHERE book=? AND chapter=? AND verse=?", args(b, c, v))) {
            if (cur.moveToFirst()) {
                user.execSQL("DELETE FROM bookmarks WHERE book=? AND chapter=? AND verse=?", new Object[]{b, c, v});
                return false;
            }
        }
        user.execSQL("INSERT INTO bookmarks VALUES(?,?,?,?)", new Object[]{b, c, v, System.currentTimeMillis()});
        return true;
    }

    public synchronized String userList(String kind) throws JSONException {
        JSONArray arr = new JSONArray();
        String sql;
        switch (kind) {
            case "notes": sql = "SELECT book, chapter, verse, text, updated FROM notes ORDER BY book, chapter, verse"; break;
            case "highlights": sql = "SELECT book, chapter, verse, color, updated FROM highlights ORDER BY book, chapter, verse"; break;
            case "bookmarks": sql = "SELECT book, chapter, verse, NULL, updated FROM bookmarks ORDER BY book, chapter, verse"; break;
            default: return arr.toString();
        }
        try (Cursor cur = user.rawQuery(sql, null)) {
            while (cur.moveToNext()) {
                JSONObject o = new JSONObject().put("b", cur.getInt(0)).put("c", cur.getInt(1)).put("v", cur.getInt(2)).put("t", cur.getLong(4));
                if (kind.equals("notes")) o.put("text", cur.getString(3));
                if (kind.equals("highlights")) o.put("color", cur.getString(3));
                arr.put(o);
            }
        }
        return arr.toString();
    }

    public synchronized String getSetting(String key) {
        try (Cursor cur = user.rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return cur.moveToFirst() ? cur.getString(0) : null;
        }
    }

    public synchronized void setSetting(String key, String value) {
        user.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", new Object[]{key, value});
    }

    public synchronized String planDone(String plan) {
        JSONArray arr = new JSONArray();
        try (Cursor cur = user.rawQuery("SELECT day FROM plan WHERE plan=? ORDER BY day", new String[]{plan})) {
            while (cur.moveToNext()) arr.put(cur.getInt(0));
        }
        return arr.toString();
    }

    public synchronized void setPlanDay(String plan, int day, boolean done) {
        if (done) user.execSQL("INSERT OR IGNORE INTO plan VALUES(?,?)", new Object[]{plan, day});
        else user.execSQL("DELETE FROM plan WHERE plan=? AND day=?", new Object[]{plan, day});
    }

    // ---------------------------------------------------------------- backup

    public synchronized String backupJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("app", "my-bible");
        o.put("version", 1);
        o.put("created", System.currentTimeMillis());
        o.put("notes", dump("SELECT book, chapter, verse, text, updated FROM notes"));
        o.put("highlights", dump("SELECT book, chapter, verse, color, updated FROM highlights"));
        o.put("bookmarks", dump("SELECT book, chapter, verse, updated FROM bookmarks"));
        o.put("plan", dump("SELECT plan, day FROM plan"));
        o.put("settings", dump("SELECT key, value FROM settings"));
        return o.toString();
    }

    private JSONArray dump(String sql) {
        JSONArray arr = new JSONArray();
        try (Cursor cur = user.rawQuery(sql, null)) {
            while (cur.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int i = 0; i < cur.getColumnCount(); i++) {
                    if (cur.getType(i) == Cursor.FIELD_TYPE_INTEGER) row.put(cur.getLong(i));
                    else row.put(cur.isNull(i) ? JSONObject.NULL : cur.getString(i));
                }
                arr.put(row);
            }
        }
        return arr;
    }

    /** Merges a backup into the user's data. Returns the number of items restored. */
    public synchronized int restore(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!"my-bible".equals(o.optString("app"))) throw new JSONException("This isn't a My Bible backup file");
        int n = 0;
        user.beginTransaction();
        try {
            n += load(o.optJSONArray("notes"), "INSERT OR REPLACE INTO notes VALUES(?,?,?,?,?)");
            n += load(o.optJSONArray("highlights"), "INSERT OR REPLACE INTO highlights VALUES(?,?,?,?,?)");
            n += load(o.optJSONArray("bookmarks"), "INSERT OR REPLACE INTO bookmarks VALUES(?,?,?,?)");
            load(o.optJSONArray("plan"), "INSERT OR REPLACE INTO plan VALUES(?,?)");
            load(o.optJSONArray("settings"), "INSERT OR REPLACE INTO settings VALUES(?,?)");
            user.setTransactionSuccessful();
        } finally {
            user.endTransaction();
        }
        return n;
    }

    private int load(JSONArray rows, String sql) throws JSONException {
        if (rows == null) return 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONArray r = rows.getJSONArray(i);
            Object[] a = new Object[r.length()];
            for (int j = 0; j < r.length(); j++) a[j] = r.isNull(j) ? null : r.get(j);
            user.execSQL(sql, a);
        }
        return rows.length();
    }

    private static String[] args(Object... a) {
        String[] s = new String[a.length];
        for (int i = 0; i < a.length; i++) s[i] = String.valueOf(a[i]);
        return s;
    }

    static String slug(String s) {
        String x = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (x.length() > 60) x = x.substring(0, 60);
        return x.isEmpty() ? "module" : x;
    }
}
