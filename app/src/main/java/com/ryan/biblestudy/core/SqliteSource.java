package com.ryan.biblestudy.core;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Adapts an e-Sword 9+ (.bblx/.bbli ...) or MySword (.mybible) SQLite file to {@link SourceDb}. */
public final class SqliteSource implements SourceDb {
    private final SQLiteDatabase db;

    public SqliteSource(File f) throws IOException {
        try {
            db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            // touch the schema so a password-protected / non-SQLite file fails here
            db.rawQuery("SELECT name FROM sqlite_master LIMIT 1", null).close();
        } catch (SQLiteException e) {
            throw new IOException("Not a readable SQLite module", e);
        }
    }

    @Override public List<String> tableNames() {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    @Override public List<String> columnNames(String table) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + quote(table) + ")", null)) {
            int idx = c.getColumnIndex("name");
            while (c.moveToNext()) out.add(c.getString(idx));
        }
        return out;
    }

    @Override public void scan(String table, RowHandler handler) throws IOException {
        try (Cursor c = db.rawQuery("SELECT * FROM " + quote(table), null)) {
            final String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                final Cursor row = c;
                boolean cont = handler.onRow(name -> {
                    int i = row.getColumnIndex(name);
                    if (i < 0) return null;
                    switch (row.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: return row.getLong(i);
                        case Cursor.FIELD_TYPE_FLOAT: return row.getDouble(i);
                        case Cursor.FIELD_TYPE_BLOB: return row.getBlob(i);
                        case Cursor.FIELD_TYPE_NULL: return null;
                        default: return row.getString(i);
                    }
                });
                if (!cont) return;
            }
        } catch (SQLiteException e) {
            throw new IOException("Could not read table " + table, e);
        }
    }

    @Override public void close() { db.close(); }

    private static String quote(String id) { return "\"" + id.replace("\"", "\"\"") + "\""; }
}
