package com.ryan.biblestudy.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** A module file opened for reading: either a legacy Jet file or an SQLite file. */
public interface SourceDb extends Closeable {

    interface Row {
        Object get(String column);
    }

    interface RowHandler {
        boolean onRow(Row row) throws IOException;
    }

    List<String> tableNames();

    List<String> columnNames(String table);

    void scan(String table, RowHandler handler) throws IOException;
}
