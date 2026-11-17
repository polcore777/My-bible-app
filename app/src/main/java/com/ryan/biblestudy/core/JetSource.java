package com.ryan.biblestudy.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Adapts a legacy e-Sword (Jet) file to {@link SourceDb}. */
public final class JetSource implements SourceDb {
    private final JetDb db;

    public JetSource(File f) throws IOException { db = new JetDb(f); }

    @Override public List<String> tableNames() { return new ArrayList<>(db.tables().keySet()); }

    @Override public List<String> columnNames(String table) {
        List<String> out = new ArrayList<>();
        JetDb.Table t = db.table(table);
        if (t != null) for (JetDb.Column c : t.columns) out.add(c.name);
        return out;
    }

    @Override public void scan(String table, final RowHandler handler) throws IOException {
        JetDb.Table t = db.table(table);
        if (t == null) return;
        db.scan(t, row -> handler.onRow(row::get));
    }

    @Override public void close() throws IOException { db.close(); }
}
