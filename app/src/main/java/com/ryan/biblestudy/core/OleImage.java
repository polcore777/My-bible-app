package com.ryan.biblestudy.core;

/** Pulls a displayable image (JPEG, PNG, GIF, BMP) out of an OLE object or raw blob. */
public final class OleImage {
    private OleImage() { }

    /** @return {mime, bytes} or null */
    public static Object[] extract(byte[] b) {
        if (b == null || b.length < 8) return null;
        int lim = Math.min(b.length - 4, 8192); // signatures sit near the start of the OLE wrapper
        for (int i = 0; i < lim; i++) {
            int a0 = b[i] & 0xFF, a1 = b[i + 1] & 0xFF, a2 = b[i + 2] & 0xFF, a3 = b[i + 3] & 0xFF;
            if (a0 == 0xFF && a1 == 0xD8 && a2 == 0xFF) return slice(b, i, "image/jpeg");
            if (a0 == 0x89 && a1 == 'P' && a2 == 'N' && a3 == 'G') return slice(b, i, "image/png");
            if (a0 == 'G' && a1 == 'I' && a2 == 'F' && a3 == '8') return slice(b, i, "image/gif");
            if (a0 == 'B' && a1 == 'M' && i + 14 < b.length) {
                long size = (a2 | (a3 << 8) | ((b[i + 4] & 0xFF) << 16) | ((long) (b[i + 5] & 0xFF) << 24));
                if (size > 54 && i + size <= b.length + 16) {
                    byte[] out = new byte[(int) Math.min(size, b.length - i)];
                    System.arraycopy(b, i, out, 0, out.length);
                    return new Object[]{"image/bmp", out};
                }
            }
        }
        return null;
    }

    private static Object[] slice(byte[] b, int from, String mime) {
        byte[] out = new byte[b.length - from];
        System.arraycopy(b, from, out, 0, out.length);
        return new Object[]{mime, out};
    }
}
