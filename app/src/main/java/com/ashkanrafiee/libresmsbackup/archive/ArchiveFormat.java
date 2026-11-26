package com.ashkanrafiee.libresmsbackup.archive;

import android.content.ContentUris;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads and writes the streaming JSON payload inside a Libre SMS Backup archive. */
public final class ArchiveFormat {
    public static final String FILE_EXTENSION = ".lsb";
    public static final String ENCRYPTED_FILE_EXTENSION = ".lsb.enc";
    public static final int PAYLOAD_SCHEMA = 2;
    // Shared with the streaming restore reader.
    public static final int MAX_OBJECT_FIELDS = 1024;
    public static final int MAX_TOKEN_BYTES = 4 * 1024 * 1024;
    public static final int MAX_RECORD_METADATA_BYTES = 8 * 1024 * 1024;
    public static final int MAX_MMS_METADATA_BYTES = MAX_RECORD_METADATA_BYTES;
    public static final int MAX_MMS_CHILDREN = 4096;
    public static final int MAX_THREAD_RECIPIENTS = 256;
    public static final int MAX_THREAD_RECIPIENT_LENGTH = 512;
    public static final int MAX_ATTACHMENT_BYTES = 64 * 1024 * 1024;
    private static final Uri MMS_URI = Uri.parse("content://mms");
    private static final Uri SIMPLE_CONVERSATIONS_URI =
            Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL_ADDRESS_URI = Uri.parse("content://mms-sms/canonical-address");
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
        AttachmentCounts attachments = new AttachmentCounts();
        try {
            try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(temp));
                 AttachmentJsonOutput payload = new AttachmentJsonOutput(new OutputStreamWriter(
                         ArchiveCrypto.openOutput(raw, context, password, encrypted), StandardCharsets.UTF_8));
                 JsonWriter json = new JsonWriter(payload)) {
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
                            writeCursorRow(json, cursor, null);
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
                            writeCursorRow(json, cursor, null);
                            calls++;
                        }
                    }
                }
                json.endArray();
                json.name("mms");
                mms = writeMmsSection(context, json, payload, includeSms, attachments);
                json.name("rcs");
                rcs = writeRcsSection(context, json, payload, includeSms, attachments);
                json.endObject();
                // Closing JsonWriter also finalizes the encrypted payload.
            }
            copyFileToUri(context, temp, destination);
            return new BackupSummary(sms, calls, mms, rcs, attachments.omitted);
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
        File stage = null;
        FileInputStream plaintext = null;
        boolean ownershipTransferred = false;
        try {
            stage = File.createTempFile("libresmsbackup-restore-", ".json", context.getCacheDir());
            plaintext = new FileInputStream(stage);
            boolean encrypted;
            try (FileOutputStream output = new FileOutputStream(stage)) {
                // Keep only the open descriptors. The OS frees the staged data even if the app dies.
                if (!stage.delete()) throw new IOException("cannot unlink restore stage");
                stage = null;
                try (InputStream raw = context.getContentResolver().openInputStream(source)) {
                    if (raw == null) throw new IOException("cannot open source");
                    encrypted = ArchiveCrypto.stagePayload(new BufferedInputStream(raw), output, password);
                }
            }
            PreparedRestore prepared = new PreparedRestore(plaintext, encrypted);
            ownershipTransferred = true;
            return prepared;
        } finally {
            Arrays.fill(password, '\0');
            if (stage != null) stage.delete();
            if (!ownershipTransferred && plaintext != null) closeStage(plaintext);
        }
    }

    private static void closeStage(FileInputStream stage) {
        try { stage.close(); } catch (IOException ignored) { }
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
        private final FileInputStream plaintext;
        public final RestoreEngine.ArchiveSummary summary;
        public final boolean encrypted;
        private boolean closed;

        private PreparedRestore(FileInputStream plaintext, boolean encrypted) throws IOException {
            this.plaintext = plaintext;
            this.summary = RestoreEngine.inspect(plaintext.getChannel());
            this.encrypted = encrypted;
        }

        public RestoreEngine.RestoreSummary restore(Context context,
                boolean includeSms, boolean includeCalls) throws Exception {
            return restore(context, includeSms, includeCalls, null);
        }

        public synchronized RestoreEngine.RestoreSummary restore(Context context,
                boolean includeSms, boolean includeCalls,
                RestoreEngine.ProgressListener listener) throws Exception {
            if (closed) throw new IOException("restore session is closed");
            return RestoreEngine.restore(context, plaintext.getChannel(), includeSms, includeCalls,
                    includeSms, includeSms, summary, listener);
        }

        @Override public synchronized void close() {
            if (!closed) {
                closed = true;
                closeStage(plaintext);
            }
        }
    }

    private static void writeCursorRow(JsonWriter json, Cursor cursor, MmsMetadata metadata)
            throws IOException {
        int fields = 0;
        Set<String> exported = new HashSet<>();
        int rowBytes = 2; // Row braces.
        if (metadata != null) metadata.add(rowBytes);
        json.beginObject();
        for (String providerColumn : cursor.getColumnNames()) {
            String column = RestoreEngine.normalizedColumnName(providerColumn);
            if (column == null || isNonPortableColumn(column) || !exported.add(column)) continue;
            if (++fields > MAX_OBJECT_FIELDS) throw new MetadataLimitException("backup object has too many fields");
            int fieldBytes = jsonStringBytes(column) + (fields == 1 ? 1 : 2); // Colon and comma.
            json.name(column);
            int index = cursor.getColumnIndex(providerColumn);
            int valueBytes;
            if (index < 0 || cursor.isNull(index)) {
                json.nullValue();
                valueBytes = 4;
            } else {
                switch (cursor.getType(index)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        long integer = cursor.getLong(index);
                        json.value(integer);
                        valueBytes = Long.toString(integer).length();
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        double number = cursor.getDouble(index);
                        json.value(number);
                        valueBytes = Double.toString(number).length();
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        byte[] blob = cursor.getBlob(index);
                        valueBytes = checkedStringBytes((blob.length + 2L) / 3 * 4)
                                + "{\"encoding\":\"base64\",\"data\":}".length();
                        json.beginObject();
                        json.name("encoding").value("base64");
                        json.name("data").value(Base64.encodeToString(blob, Base64.NO_WRAP));
                        json.endObject();
                        break;
                    default:
                        String value = cursor.getString(index);
                        valueBytes = jsonStringBytes(value);
                        json.value(value);
                        break;
                }
            }
            fieldBytes += valueBytes;
            rowBytes += fieldBytes;
            if (metadata != null) metadata.add(fieldBytes);
            if (rowBytes > MAX_RECORD_METADATA_BYTES)
                throw new MetadataLimitException("backup row is too large");
        }
        json.endObject();
    }

    // Counts compact Android JsonWriter output in UTF-8 without allocating an escaped copy.
    private static int jsonStringBytes(String value) throws IOException {
        if (value == null) return 4; // null
        if (value.length() >= MAX_TOKEN_BYTES) throw new MetadataLimitException("backup metadata value is too large");
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': case '\\': case '\t': case '\b': case '\n': case '\r': case '\f':
                    bytes += 2;
                    break;
                default:
                    if (c <= 0x1f || c == '\u2028' || c == '\u2029') bytes += 6;
                    else if (c < 0x80) bytes++;
                    else if (c < 0x800) bytes += 2;
                    else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        bytes += 4;
                        i++;
                    } else {
                        bytes += 3; // Upper bound for a malformed surrogate replaced by the encoder.
                    }
            }
        }
        return checkedStringBytes(bytes);
    }

    private static int checkedStringBytes(long contentBytes) throws IOException {
        // AttachmentJsonInput counts the closing quote as part of the token.
        if (contentBytes >= MAX_TOKEN_BYTES) throw new MetadataLimitException("backup metadata value is too large");
        return (int) contentBytes + 2;
    }

    private static boolean isNonPortableColumn(String column) {
        return "_id".equals(column) || "thread_id".equals(column) || "person".equals(column)
                || "contact_id".equals(column) || "rcsdb_id".equals(column) || "_data".equals(column)
                || "sub_id".equals(column) || "phone_id".equals(column) || "index_on_icc".equals(column)
                || "raw_contact_id".equals(column);
    }

    private static int writeMmsSection(Context context, JsonWriter json, AttachmentJsonOutput payload,
            boolean enabled, AttachmentCounts attachments) throws IOException {
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
                MmsMetadata metadata = new MmsMetadata(ref);
                json.beginObject();
                json.name("ref").value(ref);
                json.name("row"); writeCursorRow(json, messages, metadata);
                int dateIndex = messages.getColumnIndex("date");
                if (dateIndex >= 0 && !messages.isNull(dateIndex)) {
                    long dateMs = mmsSecondsToMillis(messages.getLong(dateIndex));
                    metadata.add(",\"dateMs\":".length() + Long.toString(dateMs).length());
                    json.name("dateMs").value(dateMs);
                }
                json.name("parts").beginArray();
                writeMmsParts(context, json, payload, ref, metadata, attachments);
                json.endArray();
                json.name("addresses").beginArray();
                writeMmsAddresses(context, json, ref, metadata);
                json.endArray();
                writeMmsThreadRecipients(context, json, messages, metadata);
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

    private static void writeMmsThreadRecipients(Context context, JsonWriter json, Cursor message,
            MmsMetadata metadata) throws IOException {
        List<String> recipients = readMmsThreadRecipients(context, message);
        if (recipients == null) return;

        int bytes = ",\"threadRecipients\":[]".length() + recipients.size() - 1;
        for (String recipient : recipients) bytes += jsonStringBytes(recipient);
        if (!metadata.tryAdd(bytes)) return;

        json.name("threadRecipients").beginArray();
        for (String recipient : recipients) json.value(recipient);
        json.endArray();
    }

    private static List<String> readMmsThreadRecipients(Context context, Cursor message) {
        try {
            int threadIndex = message.getColumnIndex("thread_id");
            if (threadIndex < 0 || message.isNull(threadIndex)) return null;
            long threadId = message.getLong(threadIndex);
            if (threadId <= 0) return null;

            String ids = querySingleString(context, SIMPLE_CONVERSATIONS_URI, "recipient_ids",
                    "_id=?", new String[] { Long.toString(threadId) });
            // AOSP stores space-separated positive longs (up to 19 digits plus a separator).
            if (ids == null || ids.length() > MAX_THREAD_RECIPIENTS * 20) return null;
            String[] recipientIds = ids.trim().split(" +");
            if (recipientIds.length > MAX_THREAD_RECIPIENTS) return null;

            List<String> recipients = new ArrayList<>(recipientIds.length);
            for (String recipientId : recipientIds) {
                long id = Long.parseLong(recipientId);
                if (id <= 0) return null;
                String address = querySingleString(context,
                        ContentUris.withAppendedId(CANONICAL_ADDRESS_URI, id), "address", null, null);
                if (address == null || address.length() > MAX_THREAD_RECIPIENT_LENGTH
                        || address.trim().isEmpty() || "insert-address-token".equals(address)) {
                    return null;
                }
                recipients.add(address);
            }
            return recipients;
        } catch (RuntimeException unavailable) {
            // Thread recipients are optional; never emit a partially resolved list.
            return null;
        }
    }

    private static String querySingleString(Context context, Uri uri, String column,
            String selection, String[] selectionArgs) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[] { column },
                selection, selectionArgs, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int index = cursor.getColumnIndex(column);
            if (index < 0 || cursor.isNull(index)) return null;
            String value = cursor.getString(index);
            return cursor.moveToNext() ? null : value;
        }
    }

    private static void writeMmsParts(Context context, JsonWriter json, AttachmentJsonOutput payload,
            String ref, MmsMetadata metadata, AttachmentCounts attachments)
            throws IOException {
        Uri uri = Uri.parse("content://mms/" + ref + "/part");
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, "seq ASC")) {
            if (cursor == null) throw new IOException("MMS parts provider unavailable");
            while (cursor.moveToNext()) {
                metadata.addChild(ref);
                json.beginObject();
                json.name("messageRef").value(ref);
                json.name("row"); writeCursorRow(json, cursor, metadata);
                int id = cursor.getColumnIndex("_id");
                int type = cursor.getColumnIndex("ct");
                String contentType = type < 0 ? null : cursor.getString(type);
                int text = cursor.getColumnIndex("text");
                boolean savedInline = storesMmsPartInline(contentType)
                        && text >= 0 && !cursor.isNull(text) && !hasExternalMmsData(cursor);
                boolean saved = id >= 0 && !cursor.isNull(id) && writeProviderData(context, json, payload,
                        Uri.parse("content://mms/part/" + cursor.getLong(id)), metadata);
                if (!saved && !savedInline) {
                    attachments.omitted++;
                }
                json.endObject();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MMS parts cannot be read", e);
        }
    }

    public static boolean storesMmsPartInline(String contentType) {
        return "text/plain".equals(contentType) || "application/smil".equals(contentType);
    }

    private static boolean hasExternalMmsData(Cursor cursor) {
        int path = cursor.getColumnIndex("_data");
        if (path < 0 || cursor.isNull(path)) return false;
        String location = cursor.getString(path);
        return location != null && !location.isEmpty();
    }

    private static void writeMmsAddresses(Context context, JsonWriter json, String ref, MmsMetadata metadata)
            throws IOException {
        Uri uri = Uri.parse("content://mms/" + ref + "/addr");
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null) throw new IOException("MMS address provider unavailable");
            while (cursor.moveToNext()) {
                metadata.addChild(ref);
                json.beginObject();
                json.name("messageRef").value(ref);
                json.name("row"); writeCursorRow(json, cursor, metadata);
                json.endObject();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MMS addresses cannot be read", e);
        }
    }

    private static final class MmsMetadata {
        private int bytes;
        private int children;

        MmsMetadata(String ref) {
            // Count wrapper fields, array delimiters and the closing brace conservatively.
            bytes = "{\"ref\":\"\",\"row\":,\"parts\":[],\"addresses\":[]}".length() + ref.length();
        }

        void add(int count) throws IOException {
            if (!tryAdd(count)) throw new MetadataLimitException("MMS metadata is too large");
        }

        boolean tryAdd(int count) {
            if (count > MAX_MMS_METADATA_BYTES - bytes) return false;
            bytes += count;
            return true;
        }

        void addChild(String ref) throws IOException {
            if (++children > MAX_MMS_CHILDREN) throw new MetadataLimitException("MMS has too many parts or addresses");
            // Include a separator even for the first child of each array.
            add(",{\"messageRef\":\"\",\"row\":}".length() + ref.length());
        }
    }

    private static boolean writeProviderData(Context context, JsonWriter json, AttachmentJsonOutput payload,
            Uri uri, MmsMetadata metadata) throws IOException {
        InputStream source;
        try {
            source = context.getContentResolver().openInputStream(uri);
        } catch (IOException | SecurityException | IllegalArgumentException unavailable) {
            return false;
        }
        if (source == null) return false;
        File stage = null;
        boolean sourceClosed = false;
        try {
            stage = File.createTempFile("libresmsbackup-part-", ".tmp", context.getCacheDir());
            try (FileInputStream staged = new FileInputStream(stage)) {
                boolean complete;
                try (FileOutputStream output = new FileOutputStream(stage)) {
                    if (!stage.delete()) throw new IOException("cannot unlink backup attachment stage");
                    complete = stageProviderData(source, output);
                }
                sourceClosed = true;
                try { source.close(); } catch (IOException unavailable) { return false; }
                if (!complete) return false;
                // The reader substitutes offset:length (at most two 19-digit numbers and a colon).
                if (metadata != null) metadata.add(",\"dataBase64\":\"\"".length() + 2 * 19 + 1);
                payload.writeAttachment(json, staged);
                return true;
            }
        } finally {
            if (!sourceClosed) try { source.close(); } catch (IOException ignored) { }
            if (stage != null) stage.delete();
        }
    }

    private static boolean stageProviderData(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int remaining = MAX_ATTACHMENT_BYTES;
        try {
            while (true) {
                int count;
                try {
                    count = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
                } catch (IOException unavailable) {
                    return false;
                }
                if (count == -1) return true;
                if (count == 0 || count > remaining) return false;
                output.write(buffer, 0, count);
                remaining -= count;
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static int writeRcsSection(Context context, JsonWriter json, AttachmentJsonOutput payload,
            boolean enabled, AttachmentCounts attachments) throws IOException {
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
                json.name("row"); writeCursorRow(json, chats, null);
                json.endObject();
                count++;
            }
            json.endArray();
            json.name("files").beginArray();
            try (Cursor files = queryRcsFiles(context)) {
                if (files != null) while (files.moveToNext()) {
                    json.beginObject();
                    json.name("row"); writeCursorRow(json, files, null);
                    int id = files.getColumnIndex("_id");
                    if (id < 0 || files.isNull(id) || !writeProviderData(context, json, payload,
                            Uri.parse("content://im/ft/" + files.getLong(id)), null)) {
                        attachments.omitted++;
                    }
                    json.endObject();
                }
            }
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

    private static Cursor queryRcsFiles(Context context) {
        try {
            return context.getContentResolver().query(RCS_FILE_URI, null, null, null, "date ASC");
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static long mmsSecondsToMillis(long value) {
        return value > 9_999_999_999L ? value : value * 1000L;
    }

    private static void copyFileToUri(Context context, File source, Uri destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
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
        public final int omittedAttachments;
        BackupSummary(int smsCount, int callCount, int mmsCount, int rcsCount, int omittedAttachments) {
            this.smsCount = smsCount;
            this.callCount = callCount;
            this.mmsCount = mmsCount;
            this.rcsCount = rcsCount;
            this.omittedAttachments = omittedAttachments;
        }
    }

    private static final class AttachmentCounts {
        int omitted;
    }

    public static final class MetadataLimitException extends IOException {
        MetadataLimitException(String message) { super(message); }
    }
}
