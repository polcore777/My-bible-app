package com.ryan.biblestudy;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.ryan.biblestudy.core.Converter;
import com.ryan.biblestudy.core.JetDb;
import com.ryan.biblestudy.core.JetSource;
import com.ryan.biblestudy.core.ModuleSink;
import com.ryan.biblestudy.core.SourceDb;
import com.ryan.biblestudy.core.SqliteSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports an e-Sword or MySword file into the library as one of the app's own module databases. */
final class Importer {
    private final Library lib;

    Importer(Library lib) { this.lib = lib; }

    /** Imports one picked file. Appends a result object to {@code out}. */
    void importUri(ContentResolver cr, Uri uri, JSONArray out) {
        String name = displayName(cr, uri);
        File tmp = null;
        try {
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                importZip(cr, uri, name, out);
                return;
            }
            if (!Converter.isModuleFile(name)) {
                out.put(result(name, false, null, null, "Not an e-Sword or MySword module"));
                return;
            }
            tmp = File.createTempFile("imp", ".dat", lib.dir);
            copy(cr, uri, tmp);
            importFile(tmp, name, out);
        } catch (Exception e) {
            out.put(result(name, false, null, null, friendly(e)));
        } finally {
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private void importZip(ContentResolver cr, Uri uri, String zipName, JSONArray out) throws IOException {
        boolean any = false;
        try (InputStream in = cr.openInputStream(uri); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = new File(e.getName()).getName();
                if (e.isDirectory() || !Converter.isModuleFile(n)) continue;
                any = true;
                File tmp = File.createTempFile("imp", ".dat", lib.dir);
                try (OutputStream os = new FileOutputStream(tmp)) { pipe(zis, os); }
                try { importFile(tmp, n, out); }
                catch (Exception ex) { out.put(result(n, false, null, null, friendly(ex))); }
                finally { //noinspection ResultOfMethodCallIgnored
                    tmp.delete(); }
            }
        }
        if (!any) out.put(result(zipName, false, null, null, "No modules found inside the zip"));
    }

    private void importFile(File tmp, String name, JSONArray out) throws Exception {
        SourceDb src = openSource(tmp, name);
        try {
            String id = uniqueId(name);
            File target = new File(lib.dir, id + ".db");
            File building = new File(lib.dir, id + ".building");
            AndroidSink sink = new AndroidSink(building);
            Converter.Result r;
            try {
                r = Converter.convert(src, name, sink);
            } finally {
                sink.closeQuietly();
            }
            if (r.count == 0) {
                //noinspection ResultOfMethodCallIgnored
                building.delete();
                out.put(result(name, false, null, null, "The module is empty"));
                return;
            }
            lib.closeModule(id);
            if (!building.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                building.delete();
                throw new IOException("Could not save the module");
            }
            out.put(result(name, true, r.type, r.title, null).put("count", r.count).put("id", id));
        } finally {
            src.close();
        }
    }

    private SourceDb openSource(File f, String name) throws IOException {
        // sniff the header: "Standard Jet DB" -> legacy Access; "SQLite format 3" -> sqlite
        byte[] head = new byte[16];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n = in.read(head);
            if (n < 16) throw new IOException("File is too small to be a module");
        }
        String magic = new String(head, 0, 15, "ISO-8859-1");
        if (magic.equals("SQLite format 3")) return new SqliteSource(f);
        if (head[0] == 0 && head[4] == 'S' && head[5] == 't' && head[6] == 'a') {
            try {
                return new JetSource(f);
            } catch (JetDb.LockedException e) {
                throw new IOException("This module is locked (password-protected), so it can't be opened here.");
            }
        }
        throw new IOException("Unrecognised file format");
    }

    // --------------------------------------------------------------- helpers

    private String uniqueId(String name) {
        String base = Library.slug(Converter.baseName(name));
        String id = base;
        int i = 2;
        while (new File(lib.dir, id + ".db").exists() || new File(lib.dir, id + ".building").exists())
            id = base + "-" + (i++);
        return id;
    }

    static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "Couldn't read this file";
        if (m.contains("locked") || m.toLowerCase(Locale.ROOT).contains("password")) return "This module is locked (password-protected)";
        return m;
    }

    private static JSONObject result(String file, boolean ok, String type, String title, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("file", file); o.put("ok", ok);
            if (type != null) o.put("type", type);
            if (title != null) o.put("title", title);
            if (error != null) o.put("error", error);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { }
        String p = uri.getLastPathSegment();
        return p == null ? "module" : new File(p).getName();
    }

    private static void copy(ContentResolver cr, Uri uri, File dst) throws IOException {
        try (InputStream in = cr.openInputStream(uri); OutputStream os = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Could not open the file");
            pipe(in, os);
        }
    }

    private static void pipe(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
    }

    /** Writes converted module content straight into a new SQLite module database. */
    static final class AndroidSink implements ModuleSink {
        private final SQLiteDatabase db;
        private String type;
        private SQLiteDatabase.CursorFactory nf;

        AndroidSink(File file) {
            db = SQLiteDatabase.openOrCreateDatabase(file, null);
            db.execSQL("PRAGMA journal_mode=OFF");
            db.execSQL("PRAGMA synchronous=OFF");
        }

        @Override public void begin(String type) {
            this.type = type;
            db.beginTransaction();
            db.execSQL("CREATE TABLE info(key TEXT PRIMARY KEY, value TEXT)");
            switch (type) {
                case BIBLE:
                    db.execSQL("CREATE TABLE verses(book INT, chapter INT, verse INT, html TEXT, plain TEXT, PRIMARY KEY(book,chapter,verse))");
                    break;
                case COMMENTARY:
                    db.execSQL("CREATE TABLE comments(book INT, ch1 INT, v1 INT, ch2 INT, v2 INT, html TEXT)");
                    break;
                case DICTIONARY:
                    db.execSQL("CREATE TABLE entries(key TEXT, html TEXT, plain TEXT)");
                    break;
                case IMAGES:
                    db.execSQL("CREATE TABLE images(id INT PRIMARY KEY, title TEXT, mime TEXT, data BLOB)");
                    break;
                default:
                    db.execSQL("CREATE TABLE sections(id INT PRIMARY KEY, title TEXT, html TEXT, plain TEXT)");
            }
        }

        @Override public void verse(int b, int c, int v, String html, String plain) {
            db.execSQL("INSERT OR REPLACE INTO verses VALUES(?,?,?,?,?)", new Object[]{b, c, v, html, plain});
        }
        @Override public void comment(int b, int c1, int v1, int c2, int v2, String html) {
            db.execSQL("INSERT INTO comments VALUES(?,?,?,?,?,?)", new Object[]{b, c1, v1, c2, v2, html});
        }
        @Override public void entry(String key, String html, String plain) {
            db.execSQL("INSERT INTO entries VALUES(?,?,?)", new Object[]{key, html, plain});
        }
        @Override public void section(int order, String title, String html, String plain) {
            db.execSQL("INSERT OR REPLACE INTO sections VALUES(?,?,?,?)", new Object[]{order, title, html, plain});
        }
        @Override public void image(int order, String title, String mime, byte[] data) {
            db.execSQL("INSERT OR REPLACE INTO images VALUES(?,?,?,?)", new Object[]{order, title, mime, data});
        }

        @Override public void end(java.util.Map<String, String> info) {
            for (java.util.Map.Entry<String, String> e : info.entrySet())
                db.execSQL("INSERT OR REPLACE INTO info VALUES(?,?)", new Object[]{e.getKey(), e.getValue()});
            if (COMMENTARY.equals(type))
                db.execSQL("CREATE INDEX comments_book ON comments(book, ch1)");
            if (DICTIONARY.equals(type))
                db.execSQL("CREATE INDEX entries_key ON entries(key COLLATE NOCASE)");
            db.setTransactionSuccessful();
            db.endTransaction();
        }

        void closeQuietly() {
            try { if (db.inTransaction()) db.endTransaction(); } catch (Exception ignored) { }
            try { db.close(); } catch (Exception ignored) { }
        }
    }
}
