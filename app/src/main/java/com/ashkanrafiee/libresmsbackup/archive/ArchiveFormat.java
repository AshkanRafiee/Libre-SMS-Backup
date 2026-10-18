package com.ashkanrafiee.libresmsbackup.archive;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.Telephony;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import com.ashkanrafiee.libresmsbackup.data.RestoreEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads and writes the streaming JSON payload inside a Libre SMS Backup archive. */
public final class ArchiveFormat {
    public static final String FILE_EXTENSION = ".lsb";
    public static final String ENCRYPTED_FILE_EXTENSION = ".lsb.enc";
    public static final int PAYLOAD_SCHEMA = 2;
    private static final long MAX_RESTORE_PAYLOAD_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_MMS_PART_BYTES = 64L * 1024L * 1024L;
    private static final Uri MMS_URI = Uri.parse("content://mms");
    private static final Uri RCS_CHAT_URI = Uri.parse("content://im/chat");
    private static final Uri RCS_FILE_URI = Uri.parse("content://im/ft");
    private ArchiveFormat() {}

    public static synchronized BackupSummary create(Context context, Uri destination, char[] password,
            boolean encrypted, boolean includeSms, boolean includeCalls) throws Exception {
        return create(context, destination, password, encrypted, includeSms, includeCalls, false);
    }

    public static synchronized BackupSummary create(Context context, Uri destination, char[] password,
            boolean encrypted, boolean includeSms, boolean includeCalls,
            boolean includeRestrictedMessages) throws Exception {
        if (destination == null || (encrypted && password == null)) throw new IllegalArgumentException();
        File temp = File.createTempFile("libresmsbackup-", ".tmp", context.getCacheDir());
        int sms = 0;
        int calls = 0;
        int mms = 0;
        int rcs = 0;
        try {
            try (FileOutputStream raw = new FileOutputStream(temp)) {
                OutputStream payload = ArchiveCrypto.openOutput(raw, context, password, encrypted);
                JsonWriter json = new JsonWriter(new OutputStreamWriter(payload, StandardCharsets.UTF_8));
                json.beginObject();
                json.name("schema").value(PAYLOAD_SCHEMA);
                json.name("createdAtMs").value(System.currentTimeMillis());
                json.name("androidApi").value(android.os.Build.VERSION.SDK_INT);
                json.name("sms").beginArray();
                if (includeRestrictedMessages) {
                    com.ashkanrafiee.libresmsbackup.SmsAccess.requireReadable(context);
                }
                if (includeSms) {
                    try (Cursor cursor = context.getContentResolver().query(
                            Telephony.Sms.CONTENT_URI, null, null, null, "date ASC")) {
                        if (cursor == null) throw new IOException("SMS provider unavailable");
                        while (cursor.moveToNext()) {
                            writeCursorRow(json, cursor, cursor.getColumnNames());
                            sms++;
                        }
                    }
                }
                json.endArray();
                json.name("calls").beginArray();
                if (includeCalls) {
                    try (Cursor cursor = context.getContentResolver().query(
                            CallLog.Calls.CONTENT_URI, null, null, null, "date ASC")) {
                        if (cursor == null) throw new IOException("call-log provider unavailable");
                        while (cursor.moveToNext()) {
                            writeCursorRow(json, cursor, cursor.getColumnNames());
                            calls++;
                        }
                    }
                }
                json.endArray();
                json.name("mms");
                mms = writeMmsSection(context, json, includeSms);
                json.name("rcs");
                rcs = writeRcsSection(context, json, includeSms);
                json.endObject();
                json.flush();
                // Closing JsonWriter closes CipherOutputStream and writes the GCM authentication tag.
                json.close();
            }
            copyFileToUri(context, temp, destination);
            return new BackupSummary(sms, calls, mms, rcs);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            // A failed operation leaves no usable archive at the app's cache location.
            // The destination, if interrupted, is missing the GCM tag and cannot be restored.
            temp.delete();
        }
    }

    public static synchronized PreparedRestore prepareRestore(Context context, Uri source, char[] password)
            throws Exception {
        if (source == null || password == null) throw new IllegalArgumentException();
        File plaintext = File.createTempFile("libresmsbackup-restore-", ".json", context.getCacheDir());
        boolean ownershipTransferred = false;
        boolean encrypted;
        try {
            try (InputStream header = context.getContentResolver().openInputStream(source)) {
                if (header == null) throw new IOException("cannot open source");
                encrypted = ArchiveCrypto.requiresPassword(header);
            }
            try (InputStream raw = context.getContentResolver().openInputStream(source)) {
                if (raw == null) throw new IOException("cannot open source");
                try (InputStream decrypted = ArchiveCrypto.openDecrypted(raw, password);
                     FileOutputStream output = new FileOutputStream(plaintext)) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    int count;
                    while ((count = decrypted.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_RESTORE_PAYLOAD_BYTES) throw new IOException("archive payload too large");
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                    Arrays.fill(buffer, (byte) 0);
                }
            }
            // Do not touch provider data until the complete GCM payload has authenticated.
            PreparedRestore prepared = new PreparedRestore(plaintext, RestoreEngine.inspect(plaintext), encrypted);
            ownershipTransferred = true;
            return prepared;
        } finally {
            Arrays.fill(password, '\0');
            // Ownership moves to PreparedRestore only after it is constructed successfully.
            // On every failure, remove the authenticated payload from the cache immediately.
            if (!ownershipTransferred) plaintext.delete();
        }
    }

    public static synchronized RestoreEngine.RestoreSummary restore(Context context, Uri source, char[] password)
            throws Exception {
        PreparedRestore prepared = prepareRestore(context, source, password);
        try {
            return prepared.restore(context, true, true);
        } finally {
            prepared.close();
        }
    }

    public static final class PreparedRestore implements java.io.Closeable {
        private final File plaintext;
        public final RestoreEngine.ArchiveSummary summary;
        public final boolean encrypted;
        private boolean closed;

        private PreparedRestore(File plaintext, RestoreEngine.ArchiveSummary summary, boolean encrypted) {
            this.plaintext = plaintext;
            this.summary = summary;
            this.encrypted = encrypted;
        }

        public RestoreEngine.RestoreSummary restore(Context context,
                boolean includeSms, boolean includeCalls) throws Exception {
            if (closed) throw new IOException("restore session is closed");
            return RestoreEngine.restore(context, plaintext, includeSms, includeCalls);
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                plaintext.delete();
            }
        }
    }

    private static void writeCursorRow(JsonWriter json, Cursor cursor, String[] columns)
            throws IOException {
        json.beginObject();
        for (String column : columns) {
            if (isNonPortableColumn(column)) continue;
            json.name(column);
            int index = cursor.getColumnIndex(column);
            if (index < 0 || cursor.isNull(index)) {
                json.nullValue();
                continue;
            }
            switch (cursor.getType(index)) {
                case Cursor.FIELD_TYPE_INTEGER: json.value(cursor.getLong(index)); break;
                case Cursor.FIELD_TYPE_FLOAT: json.value(cursor.getDouble(index)); break;
                case Cursor.FIELD_TYPE_BLOB:
                    byte[] blob = cursor.getBlob(index);
                    json.beginObject();
                    json.name("encoding").value("base64");
                    json.name("data").value(Base64.encodeToString(blob, Base64.NO_WRAP));
                    json.endObject();
                    break;
                default: json.value(cursor.getString(index)); break;
            }
        }
        json.endObject();
    }

    private static boolean isNonPortableColumn(String column) {
        return "_id".equals(column) || "thread_id".equals(column) || "person".equals(column)
                || "contact_id".equals(column) || "rcsdb_id".equals(column) || "_data".equals(column);
    }

    private static int writeMmsSection(Context context, JsonWriter json, boolean enabled) throws IOException {
        json.beginObject();
        json.name("status");
        if (!enabled) {
            json.value("not_selected");
            json.name("messages").beginArray().endArray();
            json.name("parts").beginArray().endArray();
            json.name("addresses").beginArray().endArray();
            json.endObject();
            return 0;
        }
        Cursor messages = null;
        int count = 0;
        try {
            messages = context.getContentResolver().query(MMS_URI, null, null, null, "date ASC");
            if (messages == null) throw new IOException("MMS provider unavailable");
            json.value("available");
            json.name("messages").beginArray();
            while (messages.moveToNext()) {
                int idIndex = messages.getColumnIndex("_id");
                if (idIndex < 0 || messages.isNull(idIndex)) continue;
                String ref = String.valueOf(messages.getLong(idIndex));
                json.beginObject();
                json.name("ref").value(ref);
                json.name("row"); writeCursorRow(json, messages, messages.getColumnNames());
                int dateIndex = messages.getColumnIndex("date");
                if (dateIndex >= 0 && !messages.isNull(dateIndex))
                    json.name("dateMs").value(mmsSecondsToMillis(messages.getLong(dateIndex)));
                json.name("parts").beginArray();
                writeMmsParts(context, json, ref);
                json.endArray();
                json.name("addresses").beginArray();
                writeMmsAddresses(context, json, ref);
                json.endArray();
                json.endObject();
                count++;
            }
            json.endArray();
        } catch (SecurityException | IllegalArgumentException | IOException e) {
            // MMS is an optional provider. An unavailable provider must be recorded
            // as unavailable rather than making an otherwise valid SMS backup fail.
            if (messages == null) {
                json.value("unavailable");
                json.name("messages").beginArray().endArray();
            } else throw e;
        } finally {
            if (messages != null) messages.close();
        }
        json.endObject();
        return count;
    }

    private static void writeMmsParts(Context context, JsonWriter json, String ref) throws IOException {
        Uri uri = Uri.parse("content://mms/" + ref + "/part");
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, "seq ASC")) {
            if (cursor == null) throw new IOException("MMS parts provider unavailable");
            while (cursor.moveToNext()) {
                json.beginObject();
                json.name("messageRef").value(ref);
                json.name("row"); writeCursorRow(json, cursor, cursor.getColumnNames());
                int id = cursor.getColumnIndex("_id");
                if (id >= 0 && !cursor.isNull(id)) {
                    String data = readProviderData(context, Uri.parse("content://mms/part/" + cursor.getLong(id)));
                    if (data != null) json.name("dataBase64").value(data);
                }
                json.endObject();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MMS parts cannot be read", e);
        }
    }

    private static void writeMmsAddresses(Context context, JsonWriter json, String ref) throws IOException {
        Uri uri = Uri.parse("content://mms/" + ref + "/addr");
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null) throw new IOException("MMS address provider unavailable");
            while (cursor.moveToNext()) {
                json.beginObject();
                json.name("messageRef").value(ref);
                json.name("row"); writeCursorRow(json, cursor, cursor.getColumnNames());
                json.endObject();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MMS addresses cannot be read", e);
        }
    }

    private static String readProviderData(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            if (input == null) return null;
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_MMS_PART_BYTES) return null;
                output.write(buffer, 0, count);
            }
            Arrays.fill(buffer, (byte) 0);
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) { return null; }
    }

    private static int writeRcsSection(Context context, JsonWriter json, boolean enabled) throws IOException {
        json.beginObject();
        if (!enabled) {
            json.name("status").value("not_selected");
            json.name("chats").beginArray().endArray();
            json.name("files").beginArray().endArray();
            json.endObject();
            return 0;
        }
        int count = 0;
        Cursor chats = null;
        try {
            chats = context.getContentResolver().query(RCS_CHAT_URI, null, null, null, "date ASC");
            if (chats == null) throw new IOException("RCS provider unavailable");
            json.name("status").value("available");
            json.name("chats").beginArray();
            while (chats.moveToNext()) {
                json.beginObject();
                json.name("row"); writeCursorRow(json, chats, chats.getColumnNames());
                json.endObject();
                count++;
            }
            json.endArray();
            json.name("files").beginArray();
            try (Cursor files = context.getContentResolver().query(RCS_FILE_URI, null, null, null, "date ASC")) {
                if (files != null) while (files.moveToNext()) {
                    json.beginObject();
                    json.name("row"); writeCursorRow(json, files, files.getColumnNames());
                    int id = files.getColumnIndex("_id");
                    if (id >= 0 && !files.isNull(id)) {
                        String data = readProviderData(context, Uri.parse("content://im/ft/" + files.getLong(id)));
                        if (data != null) json.name("dataBase64").value(data);
                    }
                    json.endObject();
                }
            } catch (Exception ignored) { }
            json.endArray();
        } catch (SecurityException | IllegalArgumentException | IOException e) {
            if (chats == null) {
                json.name("status").value("unavailable");
                json.name("chats").beginArray().endArray();
                json.name("files").beginArray().endArray();
            } else throw e;
        } finally {
            if (chats != null) chats.close();
        }
        json.endObject();
        return count;
    }

    private static long mmsSecondsToMillis(long value) {
        return value > 9_999_999_999L ? value : value * 1000L;
    }

    private static void copyFileToUri(Context context, File source, Uri destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("cannot open destination");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            Arrays.fill(buffer, (byte) 0);
        }
    }

    public static final class BackupSummary {
        public final int smsCount;
        public final int callCount;
        public final int mmsCount;
        public final int rcsCount;
        BackupSummary(int smsCount, int callCount, int mmsCount, int rcsCount) {
            this.smsCount = smsCount;
            this.callCount = callCount;
            this.mmsCount = mmsCount;
            this.rcsCount = rcsCount;
        }
    }
}
