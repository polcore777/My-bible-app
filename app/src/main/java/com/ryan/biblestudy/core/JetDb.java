package com.ryan.biblestudy.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only reader for Microsoft Jet 3 / Jet 4 (Access 97 / 2000) database files,
 * which is what legacy e-Sword modules (.bbl, .cmt, .dct, .top, .map, .brp ...) are.
 *
 * Supports: catalog, fixed and variable columns, memo/OLE long values
 * (inline, single-page and multi-page), Jet4 compressed unicode, usage maps.
 * Password-protected databases are reported as locked and are not read.
 */
public final class JetDb implements Closeable {

    public static final int T_BOOL = 0x01, T_BYTE = 0x02, T_INT = 0x03, T_LONG = 0x04,
            T_MONEY = 0x05, T_FLOAT = 0x06, T_DOUBLE = 0x07, T_DATETIME = 0x08,
            T_BINARY = 0x09, T_TEXT = 0x0A, T_OLE = 0x0B, T_MEMO = 0x0C,
            T_GUID = 0x0F, T_NUMERIC = 0x10;

    public static final class LockedException extends IOException {
        public LockedException(String m) { super(m); }
    }

    public static final class Column {
        public String name;
        public int type;
        public int colNum;
        public int varColNum;
        public int fixedOffset;
        public int size;
        public boolean fixed;
        @Override public String toString() { return name + ":" + type; }
    }

    public static final class Table {
        public String name;
        public int tdefPage;
        public int numRows;
        public int numVarCols;
        public List<Column> columns = new ArrayList<>();
        int usageRow, usagePage;

        public Column column(String n) {
            for (Column c : columns) if (c.name.equalsIgnoreCase(n)) return c;
            return null;
        }
    }

    /** A decoded row. Values are keyed by column name (case-insensitive lookup via get). */
    public static final class Row {
        final Table table;
        final Object[] values;
        Row(Table t, Object[] v) { table = t; values = v; }

        public Object get(String col) {
            for (int i = 0; i < table.columns.size(); i++)
                if (table.columns.get(i).name.equalsIgnoreCase(col)) return values[i];
            return null;
        }
        public Object get(int i) { return values[i]; }
        public String str(String col) {
            Object o = get(col);
            return o == null ? null : (o instanceof byte[] ? null : o.toString());
        }
        public int integer(String col, int def) {
            Object o = get(col);
            if (o instanceof Number) return ((Number) o).intValue();
            if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
            if (o instanceof String) {
                try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException e) { return def; }
            }
            return def;
        }
        public byte[] bytes(String col) {
            Object o = get(col);
            return o instanceof byte[] ? (byte[]) o : null;
        }
        public Table table() { return table; }
    }

    public interface RowHandler {
        /** Return false to stop reading. */
        boolean onRow(Row row) throws IOException;
    }

    private final RandomAccessFile raf;
    public final boolean jet4;
    private final int pgSize;
    private final long numPages;
    private final Charset charset;
    private final int dbKey;
    private final Map<String, Table> tables = new LinkedHashMap<>();

    // tiny page cache
    private final int CACHE = 64;
    private final long[] cacheKeys = new long[CACHE];
    private final byte[][] cachePages = new byte[CACHE][];
    private int cacheNext = 0;

    // format constants (see mdbtools HACKING)
    private final int rowCountOffset, tabNumRowsOffset, tabNumColsOffset, tabNumRidxOffset,
            tabUsageMapOffset, tabColsStartOffset, tabRidxEntrySize, colFlagsOffset, colSizeOffset,
            colNumOffset, tabColEntrySize, tabColOffsetVar, tabColOffsetFixed, tabNumVarColsOffset;

    public JetDb(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        try {
            byte[] hdr = new byte[0x80 + 0x18 + 40];
            raf.seek(0);
            raf.readFully(hdr, 0, Math.min(hdr.length, (int) raf.length()));
            if (!(hdr[0] == 0 && hdr[1] == 1 && new String(hdr, 4, 15, "ISO-8859-1").equals("Standard Jet DB")))
                throw new IOException("Not an e-Sword legacy (Access/Jet) file");
            int ver = hdr[0x14] & 0xFF;
            if (ver > 1) throw new IOException("Unsupported Access version " + ver);
            jet4 = ver == 1;
            pgSize = jet4 ? 4096 : 2048;
            numPages = raf.length() / pgSize;

            // header bytes 0x18.. are RC4-obfuscated with a fixed, documented key
            int encLen = jet4 ? 128 : 126;
            Rc4.apply(new byte[]{(byte) 0xC7, (byte) 0xDA, 0x39, 0x6B}, hdr, 0x18, encLen);
            int cp = le16(hdr, 0x3C);
            dbKey = le32(hdr, 0x3E);
            checkPassword(hdr);
            Charset cs;
            try { cs = Charset.forName(cp == 0 ? "windows-1252" : ("windows-" + cp)); }
            catch (Exception e) { cs = Charset.forName("windows-1252"); }
            charset = cs;

            if (jet4) {
                rowCountOffset = 0x0c; tabNumRowsOffset = 16; tabNumVarColsOffset = 43; tabNumColsOffset = 45;
                tabNumRidxOffset = 51; tabUsageMapOffset = 55; tabColsStartOffset = 63; tabRidxEntrySize = 12;
                colFlagsOffset = 15; colSizeOffset = 23; colNumOffset = 5; tabColEntrySize = 25;
                tabColOffsetVar = 7; tabColOffsetFixed = 21;
            } else {
                rowCountOffset = 0x08; tabNumRowsOffset = 12; tabNumVarColsOffset = 23; tabNumColsOffset = 25;
                tabNumRidxOffset = 31; tabUsageMapOffset = 35; tabColsStartOffset = 43; tabRidxEntrySize = 8;
                colFlagsOffset = 13; colSizeOffset = 16; colNumOffset = 1; tabColEntrySize = 18;
                tabColOffsetVar = 3; tabColOffsetFixed = 14;
            }
            for (int i = 0; i < CACHE; i++) cacheKeys[i] = -1;
            readCatalog();
        } catch (IOException | RuntimeException e) {
            try { raf.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void checkPassword(byte[] hdr) throws LockedException {
        byte[] pwd = new byte[jet4 ? 40 : 20];
        System.arraycopy(hdr, 0x42, pwd, 0, pwd.length);
        if (jet4) {
            // Jet4 password bytes are additionally masked with the creation date
            double date = Double.longBitsToDouble(le64(hdr, 0x72));
            int m = (int) date;
            byte[] mask = {(byte) m, (byte) (m >> 8), (byte) (m >> 16), (byte) (m >> 24)};
            for (int i = 0; i < pwd.length; i++) pwd[i] ^= mask[i % 4];
            // first 4 bytes are not masked in some writers; test both variants
            boolean allZero = true;
            for (int i = 4; i < pwd.length; i++) if (pwd[i] != 0) { allZero = false; break; }
            if (allZero) return;
            byte[] raw = new byte[40];
            System.arraycopy(hdr, 0x42, raw, 0, 40);
            boolean rawZero = true;
            for (byte b : raw) if (b != 0) { rawZero = false; break; }
            if (rawZero) return;
            throw new LockedException("This module is password-protected (locked)");
        } else {
            for (byte b : pwd) if (b != 0) throw new LockedException("This module is password-protected (locked)");
        }
    }

    public Map<String, Table> tables() { return Collections.unmodifiableMap(tables); }

    public Table table(String name) {
        for (Map.Entry<String, Table> e : tables.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    @Override public void close() throws IOException { raf.close(); }

    // ------------------------------------------------------------------ pages

    private byte[] page(long pg) throws IOException {
        int slot = (int) (pg % CACHE);
        if (cacheKeys[slot] == pg) return cachePages[slot];
        if (pg < 0 || pg >= numPages) throw new IOException("Bad page " + pg);
        byte[] b = cachePages[slot];
        if (b == null) b = new byte[pgSize];
        raf.seek(pg * pgSize);
        raf.readFully(b);
        if (pg != 0 && dbKey != 0) {
            int k = dbKey ^ (int) pg;
            Rc4.apply(new byte[]{(byte) k, (byte) (k >> 8), (byte) (k >> 16), (byte) (k >> 24)}, b, 0, pgSize);
        }
        cachePages[slot] = b;
        cacheKeys[slot] = pg;
        return b;
    }

    /** Copy of the page, safe from cache eviction. */
    private byte[] pageCopy(long pg) throws IOException {
        return page(pg).clone();
    }

    /** Returns the raw bytes of row number {@code row} on page {@code pg}, following overflow pointers. */
    private byte[] rowBytes(long pg, int row) throws IOException {
        for (int hops = 0; hops < 8; hops++) {
            byte[] p = page(pg);
            int n = le16(p, rowCountOffset);
            if (row < 0 || row >= n) return null;
            int startRaw = le16(p, rowCountOffset + 2 + row * 2);
            int start = startRaw & 0x1FFF;
            int next = row == 0 ? pgSize : (le16(p, rowCountOffset + row * 2) & 0x1FFF);
            int len = next - start;
            if (len <= 0 || start + len > pgSize) return null;
            if ((startRaw & 0x4000) != 0 && (startRaw & 0x8000) == 0) {
                int ptr = le32(p, start);
                row = ptr & 0xFF;
                pg = (ptr >>> 8) & 0xFFFFFF;
                continue;
            }
            byte[] r = new byte[len];
            System.arraycopy(p, start, r, 0, len);
            return r;
        }
        return null;
    }

    // ---------------------------------------------------------------- catalog

    private void readCatalog() throws IOException {
        Table sys = readTdef(2, "MSysObjects");
        final List<Object[]> found = new ArrayList<>();
        scan(sys, row -> {
            Object type = row.get("Type");
            Object id = row.get("Id");
            Object flags = row.get("Flags");
            String name = row.str("Name");
            if (type instanceof Number && ((Number) type).intValue() == 1 && id instanceof Number && name != null) {
                int fl = flags instanceof Number ? ((Number) flags).intValue() : 0;
                if ((fl & 0x80000002) == 0 && !name.startsWith("MSys"))
                    found.add(new Object[]{name, ((Number) id).intValue() & 0x00FFFFFF});
            }
            return true;
        });
        for (Object[] o : found) {
            try {
                tables.put((String) o[0], readTdef((Integer) o[1], (String) o[0]));
            } catch (IOException e) {
                // skip unreadable table definitions
            }
        }
    }

    private Table readTdef(int tdefPg, String name) throws IOException {
        // concatenate the tdef page chain
        byte[] first = pageCopy(tdefPg);
        if (first[0] != 0x02) throw new IOException("Not a table definition page: " + tdefPg);
        byte[] buf = first;
        int next = le32(first, 4);
        int guard = 0;
        while (next != 0 && guard++ < 64) {
            byte[] p = pageCopy(next);
            byte[] nb = new byte[buf.length + pgSize - 8];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(p, 8, nb, buf.length, pgSize - 8);
            buf = nb;
            next = le32(p, 4);
        }
        Table t = new Table();
        t.name = name;
        t.tdefPage = tdefPg;
        t.numRows = le32(buf, tabNumRowsOffset);
        t.numVarCols = le16(buf, tabNumVarColsOffset);
        int numCols = le16(buf, tabNumColsOffset);
        int numRidx = le32(buf, tabNumRidxOffset);
        t.usageRow = buf[tabUsageMapOffset] & 0xFF;
        t.usagePage = le24(buf, tabUsageMapOffset + 1);

        int pos = tabColsStartOffset + numRidx * tabRidxEntrySize;
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < numCols; i++, pos += tabColEntrySize) {
            Column c = new Column();
            c.type = buf[pos] & 0xFF;
            c.colNum = le16(buf, pos + colNumOffset);
            c.varColNum = le16(buf, pos + tabColOffsetVar);
            c.fixed = (buf[pos + colFlagsOffset] & 0x01) != 0;
            c.fixedOffset = le16(buf, pos + tabColOffsetFixed);
            c.size = c.type == T_BOOL ? 0 : le16(buf, pos + colSizeOffset);
            cols.add(c);
        }
        for (Column c : cols) {
            if (jet4) {
                int len = le16(buf, pos); pos += 2;
                c.name = decodeText(buf, pos, len); pos += len;
            } else {
                int len = buf[pos] & 0xFF; pos += 1;
                c.name = new String(buf, pos, len, charset); pos += len;
            }
        }
        Collections.sort(cols, (a, b) -> Integer.compare(a.colNum, b.colNum));
        t.columns = cols;
        return t;
    }

    // ------------------------------------------------------------------ rows

    private List<Integer> dataPages(Table t) throws IOException {
        List<Integer> pages = new ArrayList<>();
        byte[] map = rowBytes(t.usagePage, t.usageRow);
        boolean ok = false;
        if (map != null && map.length > 5) {
            int type = map[0] & 0xFF;
            if (type == 0) {
                int start = le32(map, 1);
                for (int i = 0; i < (map.length - 5) * 8; i++)
                    if ((map[5 + i / 8] & (1 << (i % 8))) != 0) pages.add(start + i);
                ok = true;
            } else if (type == 1) {
                int bitlen = (pgSize - 4) * 8;
                int mapPages = (map.length - 1) / 4;
                for (int m = 0; m < mapPages; m++) {
                    int mpg = le32(map, 1 + m * 4);
                    if (mpg <= 0 || mpg >= numPages) continue;
                    byte[] p = page(mpg);
                    if (p[0] != 0x05) continue;
                    for (int i = 0; i < bitlen; i++)
                        if ((p[4 + i / 8] & (1 << (i % 8))) != 0) pages.add(m * bitlen + i);
                }
                ok = true;
            }
        }
        if (!ok) { // fall back to a full scan
            for (int pg = 1; pg < numPages; pg++) pages.add(pg);
        }
        return pages;
    }

    /** Reads every row of the table in storage order. */
    public void scan(Table t, RowHandler h) throws IOException {
        List<Integer> pages = dataPages(t);
        for (int pg : pages) {
            if (pg <= 0 || pg >= numPages) continue;
            byte[] p = pageCopy(pg);
            if (p[0] != 0x01 || le32(p, 4) != t.tdefPage) continue;
            int n = le16(p, rowCountOffset);
            for (int r = 0; r < n; r++) {
                int startRaw = le16(p, rowCountOffset + 2 + r * 2);
                if ((startRaw & 0x8000) != 0) continue; // deleted / overflow target
                byte[] rb;
                if ((startRaw & 0x4000) != 0) {
                    rb = rowBytes(pg, r);
                } else {
                    int start = startRaw & 0x1FFF;
                    int next = r == 0 ? pgSize : (le16(p, rowCountOffset + r * 2) & 0x1FFF);
                    if (next <= start || next > pgSize) continue;
                    rb = new byte[next - start];
                    System.arraycopy(p, start, rb, 0, rb.length);
                }
                if (rb == null || rb.length == 0) continue;
                Object[] vals = crackRow(t, rb);
                if (vals == null) continue;
                if (!h.onRow(new Row(t, vals))) return;
            }
        }
    }

    private Object[] crackRow(Table t, byte[] r) throws IOException {
        int len = r.length;
        int end = len - 1; // inclusive
        int rowCols = jet4 ? le16(r, 0) : (r[0] & 0xFF);
        int bitmaskSz = (rowCols + 7) / 8;
        if (bitmaskSz >= len) return null;
        int nullStart = end - bitmaskSz + 1;
        int rowVarCols = 0;
        int[] varOff = null;
        if (t.numVarCols > 0) {
            rowVarCols = jet4 ? le16(r, end - bitmaskSz - 1) : (r[end - bitmaskSz] & 0xFF);
            varOff = new int[rowVarCols + 1];
            if (jet4) {
                for (int i = 0; i <= rowVarCols; i++) varOff[i] = le16(r, end - bitmaskSz - 3 - i * 2);
            } else {
                int numJumps = (len - 1) / 256;
                int colPtr = end - bitmaskSz - numJumps - 1;
                if ((colPtr - rowVarCols) / 256 < numJumps) numJumps--;
                int jumpsUsed = 0;
                for (int i = 0; i <= rowVarCols; i++) {
                    while (jumpsUsed < numJumps && i == (r[end - bitmaskSz - jumpsUsed - 1] & 0xFF)) jumpsUsed++;
                    varOff[i] = (r[colPtr - i] & 0xFF) + jumpsUsed * 256;
                }
            }
        }
        int rowFixedCols = rowCols - rowVarCols;
        int fixedFound = 0;
        int countSize = jet4 ? 2 : 1;
        Object[] out = new Object[t.columns.size()];
        for (int i = 0; i < t.columns.size(); i++) {
            Column c = t.columns.get(i);
            int byteNum = c.colNum / 8, bit = c.colNum % 8;
            boolean isNull = byteNum >= bitmaskSz || (r[nullStart + byteNum] & (1 << bit)) == 0;
            if (c.type == T_BOOL) { out[i] = !isNull; continue; }
            int start, size;
            if (c.fixed && fixedFound < rowFixedCols) {
                start = c.fixedOffset + countSize;
                size = c.size;
                fixedFound++;
            } else if (!c.fixed && varOff != null && c.varColNum < rowVarCols) {
                start = varOff[c.varColNum];
                size = varOff[c.varColNum + 1] - start;
            } else {
                continue;
            }
            if (isNull || size < 0 || start < 0 || start + size > len) continue;
            out[i] = decodeValue(c, r, start, size);
        }
        return out;
    }

    private Object decodeValue(Column c, byte[] b, int off, int size) throws IOException {
        switch (c.type) {
            case T_BYTE: return b[off] & 0xFF;
            case T_INT: return (int) (short) le16(b, off);
            case T_LONG: return le32(b, off);
            case T_MONEY: return le64(b, off) / 10000.0;
            case T_FLOAT: return Float.intBitsToFloat(le32(b, off));
            case T_DOUBLE:
            case T_DATETIME: return Double.longBitsToDouble(le64(b, off));
            case T_TEXT: return jet4 ? decodeText(b, off, size) : new String(b, off, size, charset);
            case T_BINARY: { byte[] o = new byte[size]; System.arraycopy(b, off, o, 0, size); return o; }
            case T_MEMO: {
                byte[] raw = readLval(b, off, size);
                if (raw == null) return null;
                return jet4 ? decodeText(raw, 0, raw.length) : new String(raw, charset);
            }
            case T_OLE: return readLval(b, off, size);
            case T_GUID: {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(16, size); i++) sb.append(String.format(Locale.ROOT, "%02x", b[off + i]));
                return sb.toString();
            }
            default: { byte[] o = new byte[size]; System.arraycopy(b, off, o, 0, size); return o; }
        }
    }

    private byte[] readLval(byte[] b, int off, int size) throws IOException {
        if (size < 12) return null;
        int lenRaw = le32(b, off);
        int len = lenRaw & 0x3FFFFFFF;
        if ((lenRaw & 0x80000000) != 0) { // inline
            int n = Math.min(len, size - 12);
            byte[] o = new byte[n];
            System.arraycopy(b, off + 12, o, 0, n);
            return o;
        }
        int ptr = le32(b, off + 4);
        if ((lenRaw & 0x40000000) != 0) { // single page
            byte[] r = rowBytes((ptr >>> 8) & 0xFFFFFF, ptr & 0xFF);
            if (r == null) return null;
            if (r.length > len) { byte[] o = new byte[len]; System.arraycopy(r, 0, o, 0, len); return o; }
            return r;
        }
        // multi page chain: each row starts with a 4 byte pointer to the next
        byte[] out = new byte[len];
        int pos = 0, guard = 0;
        while (ptr != 0 && pos < len && guard++ < 100000) {
            byte[] r = rowBytes((ptr >>> 8) & 0xFFFFFF, ptr & 0xFF);
            if (r == null || r.length < 4) break;
            int n = Math.min(r.length - 4, len - pos);
            System.arraycopy(r, 4, out, pos, n);
            pos += n;
            ptr = le32(r, 0);
        }
        if (pos < len) { byte[] o = new byte[pos]; System.arraycopy(out, 0, o, 0, pos); return o; }
        return out;
    }

    /** Jet4 text: UCS-2LE, optionally "compressed" (FF FE prefix, 00 toggles mode). */
    private String decodeText(byte[] b, int off, int len) {
        if (!jet4) return new String(b, off, len, charset);
        StringBuilder sb = new StringBuilder(len);
        if (len >= 2 && (b[off] & 0xFF) == 0xFF && (b[off + 1] & 0xFF) == 0xFE) {
            boolean compressed = true;
            int i = off + 2, end = off + len;
            while (i < end) {
                int c = b[i] & 0xFF;
                if (c == 0) { compressed = !compressed; i++; }
                else if (compressed) { sb.append((char) c); i++; }
                else if (i + 1 < end) { sb.append((char) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8))); i += 2; }
                else break;
            }
        } else {
            for (int i = off; i + 1 < off + len; i += 2) sb.append((char) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    static int le24(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16); }
    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
    static long le64(byte[] b, int o) {
        return (le32(b, o) & 0xFFFFFFFFL) | ((long) le32(b, o + 4) << 32);
    }

    static final class Rc4 {
        static void apply(byte[] key, byte[] data, int off, int len) {
            int[] s = new int[256];
            for (int i = 0; i < 256; i++) s[i] = i;
            for (int i = 0, j = 0; i < 256; i++) {
                j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
                int t = s[i]; s[i] = s[j]; s[j] = t;
            }
            int i = 0, j = 0;
            for (int k = 0; k < len && off + k < data.length; k++) {
                i = (i + 1) & 0xFF;
                j = (j + s[i]) & 0xFF;
                int t = s[i]; s[i] = s[j]; s[j] = t;
                data[off + k] ^= (byte) s[(s[i] + s[j]) & 0xFF];
            }
        }
    }
}
