package com.ryan.biblestudy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private Library lib;
    private Importer importer;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int PICK_FILES = 1, PICK_FOLDER = 2, EXPORT_BACKUP = 3, RESTORE_BACKUP = 4;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = new Library(this);
        importer = new Importer(lib);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFBFAF7);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u != null && ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    private void js(String code) { ui.post(() -> web.evaluateJavascript(code, null)); }
    private static String q(String s) { return org.json.JSONObject.quote(s == null ? "" : s); }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", val -> {
            if (!"true".equals(val)) finish();
        });
    }

    // ------------------------------------------------- file picking callbacks

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) {
            if (req == PICK_FILES || req == PICK_FOLDER) js("window.onImportDone && window.onImportDone('[]')");
            return;
        }
        if (req == PICK_FILES) {
            JSONArray uris = new JSONArray();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.put(data.getClipData().getItemAt(i).getUri().toString());
            } else if (data.getData() != null) uris.put(data.getData().toString());
            runImport(uris, null);
        } else if (req == PICK_FOLDER) {
            runImport(null, data.getData());
        } else if (req == EXPORT_BACKUP) {
            writeBackup(data.getData());
        } else if (req == RESTORE_BACKUP) {
            readBackup(data.getData());
        }
    }

    private void runImport(JSONArray fileUris, Uri folder) {
        io.execute(() -> {
            JSONArray out = new JSONArray();
            try {
                if (folder != null) {
                    androidx.documentfile.provider.DocumentFile root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, folder);
                    if (root != null) importTree(root, out, new int[]{0});
                } else if (fileUris != null) {
                    for (int i = 0; i < fileUris.length(); i++) {
                        Uri u = Uri.parse(fileUris.getString(i));
                        final int n = i + 1, total = fileUris.length();
                        ui.post(() -> js("window.onImportProgress && window.onImportProgress(" + q("Importing " + n + " of " + total + "…") + ")"));
                        importer.importUri(getContentResolver(), u, out);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "import failed", e);
            }
            final String json = out.toString();
            js("window.onImportProgress && window.onImportProgress('')");
            js("window.onImportDone && window.onImportDone(" + q(json) + ")");
        });
    }

    private void importTree(androidx.documentfile.provider.DocumentFile dir, JSONArray out, int[] count) {
        for (androidx.documentfile.provider.DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) { importTree(f, out, count); continue; }
            String n = f.getName();
            if (n == null) continue;
            String lower = n.toLowerCase(java.util.Locale.ROOT);
            if (!com.ryan.biblestudy.core.Converter.isModuleFile(n) && !lower.endsWith(".zip")) continue;
            final int c = ++count[0];
            ui.post(() -> js("window.onImportProgress && window.onImportProgress(" + q("Importing " + c + "… (" + f.getName() + ")") + ")"));
            importer.importUri(getContentResolver(), f.getUri(), out);
        }
    }

    private void writeBackup(Uri uri) {
        io.execute(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(lib.backupJson().getBytes("UTF-8"));
                ui.post(() -> Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void readBackup(Uri uri) {
        io.execute(() -> {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                in.close();
                int n = lib.restore(bo.toString("UTF-8"));
                js("window.onRestoreDone && window.onRestoreDone(" + q("{\"ok\":true,\"count\":" + n + "}") + ")");
            } catch (Exception e) {
                js("window.onRestoreDone && window.onRestoreDone(" + q("{\"ok\":false,\"error\":" + q(String.valueOf(e.getMessage())) + "}") + ")");
            }
        });
    }

    // ------------------------------------------------------------- JS bridge

    final class Bridge {
        @JavascriptInterface public String modules() { try { return lib.modules(); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String chapter(String id, int b, int c) { return lib.chapter(id, b, c); }
        @JavascriptInterface public String verseText(String id, int b, int c, int v) { return lib.verseText(id, b, c, v); }
        @JavascriptInterface public String search(String id, String q, String scope, boolean whole, int limit) { try { return lib.search(id, q, scope, whole, limit); } catch (Exception e) { return "{\"total\":0,\"results\":[]}"; } }
        @JavascriptInterface public String comments(String id, int b, int c, int v) { try { return lib.comments(id, b, c, v); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String entry(String id, String key) { return lib.entry(id, key); }
        @JavascriptInterface public String entryKeys(String id, String prefix, int limit) { return lib.entryKeys(id, prefix, limit); }
        @JavascriptInterface public String sections(String id) { try { return lib.sections(id); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String section(String id, int sid) { try { return lib.section(id, sid); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public String images(String id) { try { return lib.images(id); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String image(String id, int iid) { return lib.image(id, iid); }
        @JavascriptInterface public boolean deleteModule(String id) { return lib.deleteModule(id); }

        @JavascriptInterface public String userChapter(int b, int c) { try { return lib.userChapter(b, c); } catch (Exception e) { return "{\"notes\":{},\"hl\":{},\"bm\":[]}"; } }
        @JavascriptInterface public void setNote(int b, int c, int v, String t) { lib.setNote(b, c, v, t); }
        @JavascriptInterface public void setHighlight(int b, int c, int v, String color) { lib.setHighlight(b, c, v, color); }
        @JavascriptInterface public boolean toggleBookmark(int b, int c, int v) { return lib.toggleBookmark(b, c, v); }
        @JavascriptInterface public String userList(String kind) { try { return lib.userList(kind); } catch (Exception e) { return "[]"; } }

        @JavascriptInterface public String getSetting(String key) { String v = lib.getSetting(key); return v == null ? null : v; }
        @JavascriptInterface public void setSetting(String key, String value) { lib.setSetting(key, value); }
        @JavascriptInterface public String planDone(String plan) { return lib.planDone(plan); }
        @JavascriptInterface public void setPlanDay(String plan, int day, boolean done) { lib.setPlanDay(plan, day, done); }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public void share(String text) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Share verse"));
            });
        }

        @JavascriptInterface public void importFiles() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(Intent.createChooser(i, "Choose modules"), PICK_FILES); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "No file picker found", Toast.LENGTH_SHORT).show(); }
            });
        }
        @JavascriptInterface public void importFolder() {
            ui.post(() -> {
                try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Folder picking isn't available on this device", Toast.LENGTH_SHORT).show(); }
            });
        }
        @JavascriptInterface public void exportBackup() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.setType("application/json");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_TITLE, "my-bible-backup.json");
                startActivityForResult(i, EXPORT_BACKUP);
            });
        }
        @JavascriptInterface public void restoreBackup() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, RESTORE_BACKUP);
            });
        }
    }
}
