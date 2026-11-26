package com.ashkanrafiee.libresmsbackup.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Fingerprints the portable children of one MMS, independent of provider row IDs and order. */
final class MmsIdentity {
    private static final String[] ADDRESS_COLUMNS = {"address", "type", "charset"};
    private static final String[] PART_COLUMNS = {"seq", "ct", "name", "chset", "cd", "fn",
            "cid", "cl", "ctt_s", "ctt_t", "text"};
    private static final Uri MMS_URI = Uri.parse("content://mms");
    private static final Uri PART_URI = Uri.withAppendedPath(MMS_URI, "part");
    private static final int MAX_PART_BYTES = 64 * 1024 * 1024;

    private final List<String> addresses = new ArrayList<>();
    private final List<String> parts = new ArrayList<>();
    private boolean complete = true;

    void addAddress(ContentValues values) {
        addresses.add(digestKey(rowDigest(values, ADDRESS_COLUMNS)));
    }

    /** Pass null for absent data, or the SHA-256 key of the decoded bytes, including zero bytes. */
    void addPart(ContentValues values, String binaryDigest) {
        if (binaryDigest == null && requiresAttachment(values)) {
            complete = false;
            return;
        }
        MessageDigest digest = rowDigest(values, PART_COLUMNS);
        updateString(digest, binaryDigest);
        parts.add(digestKey(digest));
    }

    static boolean requiresAttachment(ContentValues part) {
        return !storesInline(part.getAsString("ct"));
    }

    /** Returns null if the archive does not contain all bytes required to identify this MMS. */
    String finish() {
        if (!complete) return null;
        MessageDigest digest = newDigest();
        updateChildren(digest, addresses);
        updateChildren(digest, parts);
        return digestKey(digest);
    }

    static String fromProvider(ContentResolver resolver, long mmsId) throws IOException {
        Uri messageUri = Uri.withAppendedPath(MMS_URI, Long.toString(mmsId));
        MmsIdentity identity = new MmsIdentity();
        byte[] buffer = null;
        // A null projection lets providers omit optional columns such as charset.
        try {
            try (Cursor cursor = resolver.query(Uri.withAppendedPath(messageUri, "addr"),
                    null, null, null, null)) {
                if (cursor == null) throw new IOException("MMS addresses unavailable");
                cursor.getColumnIndexOrThrow("address");
                cursor.getColumnIndexOrThrow("type");
                while (cursor.moveToNext()) {
                    identity.checkChildCount();
                    identity.addAddress(readRow(cursor, ADDRESS_COLUMNS));
                }
            }
            try (Cursor cursor = resolver.query(Uri.withAppendedPath(messageUri, "part"),
                    null, null, null, null)) {
                if (cursor == null) throw new IOException("MMS parts unavailable");
                cursor.getColumnIndexOrThrow("ct");
                int dataColumn = cursor.getColumnIndex("_data");
                int idColumn = cursor.getColumnIndex("_id");
                while (cursor.moveToNext()) {
                    identity.checkChildCount();
                    ContentValues values = readRow(cursor, PART_COLUMNS);
                    String binaryDigest = null;
                    // AOSP stores plain text and SMIL in the row; a populated path still
                    // requires checking the stream on providers that store these as files.
                    if (!storesInline(values.getAsString("ct")) || hasPath(cursor, dataColumn)) {
                        if (idColumn < 0 || cursor.isNull(idColumn))
                            throw new IOException("MMS part identifier unavailable");
                        if (buffer == null) buffer = new byte[32 * 1024];
                        Uri partUri = Uri.withAppendedPath(PART_URI, Long.toString(cursor.getLong(idColumn)));
                        binaryDigest = readBinaryDigest(resolver, partUri, buffer);
                    } else {
                        cursor.getColumnIndexOrThrow("text");
                    }
                    identity.addPart(values, binaryDigest);
                }
            }
            return identity.finish();
        } catch (RuntimeException unavailable) {
            throw new IOException("MMS children unavailable");
        } finally {
            if (buffer != null) Arrays.fill(buffer, (byte) 0);
        }
    }

    private void checkChildCount() throws IOException {
        if (addresses.size() + parts.size() >= ArchiveFormat.MAX_MMS_CHILDREN)
            throw new IOException("too many existing MMS children");
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    static String digestKey(MessageDigest digest) {
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    private static boolean storesInline(String contentType) {
        return "text/plain".equals(contentType) || "application/smil".equals(contentType);
    }

    private static boolean hasPath(Cursor cursor, int dataColumn) {
        return dataColumn >= 0 && !cursor.isNull(dataColumn) && !cursor.getString(dataColumn).isEmpty();
    }

    private static String readBinaryDigest(ContentResolver resolver, Uri uri, byte[] buffer)
            throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("MMS part data unavailable");
            int remaining = MAX_PART_BYTES;
            while (true) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
                if (count == -1) return digestKey(digest);
                if (count == 0 || count > remaining) throw new IOException("invalid MMS part size");
                digest.update(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static ContentValues readRow(Cursor cursor, String[] columns) {
        ContentValues row = new ContentValues(columns.length);
        for (String column : columns) {
            int index = cursor.getColumnIndex(column);
            if (index < 0 || cursor.isNull(index)) continue;
            if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) row.put(column, cursor.getBlob(index));
            else row.put(column, cursor.getString(index));
        }
        return row;
    }

    private static MessageDigest rowDigest(ContentValues row, String[] columns) {
        MessageDigest digest = newDigest();
        for (String column : columns) {
            Object value = row.get(column);
            if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                updateInt(digest, -2); // Blob marker, distinct from null or any string length.
                updateInt(digest, bytes.length);
                digest.update(bytes);
            } else {
                updateString(digest, normalizedValue(column, row.getAsString(column)));
            }
        }
        return digest;
    }

    private static String normalizedValue(String column, String value) {
        switch (column) {
            case "seq":
                // SQLite inserts 0 when a part sequence is omitted.
                return value == null || value.isEmpty() ? "0" : normalizedInteger(value);
            case "charset":
            case "chset":
                // The MMS any-charset code (0) is equivalent to an unspecified charset.
                String charset = normalizedInteger(value);
                return charset == null || charset.isEmpty() || "0".equals(charset) ? null : charset;
            case "type":
            case "ctt_s":
                return normalizedInteger(value);
            default:
                return value;
        }
    }

    private static String normalizedInteger(String value) {
        if (value == null) return null;
        try {
            return Long.toString(Long.parseLong(value));
        } catch (NumberFormatException notAnInteger) {
            return value;
        }
    }

    private static void updateChildren(MessageDigest digest, List<String> children) {
        Collections.sort(children);
        updateInt(digest, children.size());
        for (String child : children) updateString(digest, child);
    }

    private static void updateString(MessageDigest digest, String value) {
        if (value == null) {
            updateInt(digest, -1);
            return;
        }
        updateInt(digest, value.length());
        for (int i = 0; i < value.length(); i++) {
            char codeUnit = value.charAt(i);
            digest.update((byte) (codeUnit >>> 8));
            digest.update((byte) codeUnit);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
