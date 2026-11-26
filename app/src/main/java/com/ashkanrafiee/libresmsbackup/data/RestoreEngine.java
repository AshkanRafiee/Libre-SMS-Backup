package com.ashkanrafiee.libresmsbackup.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.Telephony;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restores an already authenticated archive without parsing the complete payload into memory. */
public final class RestoreEngine {
    private static final String[] SMS_KEY_COLUMNS = {"address", "date", "type", "body"};
    private static final String[] CALL_KEY_COLUMNS = {"number", "date", "duration", "type"};
    private static final String[] MMS_KEY_COLUMNS = {Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.SUBJECT, Telephony.Mms.CONTENT_TYPE, Telephony.Mms.MESSAGE_ID, Telephony.Mms.TRANSACTION_ID};
    // MMS PDU address types (WAP-209-MMSEncapsulation).
    private static final int MMS_FROM = 137;
    private static final int MMS_TO = 151;
    private static final int MMS_CC = 130;
    private static final int MAX_MMS_PART_BYTES = 64 * 1024 * 1024;

    public interface ProgressListener {
        void onProgress(int completed, int total);
    }

    private RestoreEngine() {}

    public static ArchiveSummary inspect(File plaintext) throws IOException {
        try (FileInputStream input = new FileInputStream(plaintext)) {
            return inspect(input.getChannel());
        }
    }

    public static ArchiveSummary inspect(FileChannel plaintext) throws IOException {
        ArchiveSummary result = new ArchiveSummary();
        int schema = -1;
        try (JsonReader reader = openJson(plaintext)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("schema".equals(name)) schema = reader.nextInt();
                else if ("sms".equals(name)) result.smsCount = countRecords(reader, result, true);
                else if ("calls".equals(name)) result.callsCount = countRecords(reader, result, false);
                else if ("mms".equals(name)) result.mmsCount = countNestedArray(reader, "messages");
                else if ("rcs".equals(name)) result.rcsCount = countNestedArray(reader, "chats");
                else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("unexpected data after backup payload");
        }
        if (schema != com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat.PAYLOAD_SCHEMA)
            throw new IOException("unsupported backup payload version");
        return result;
    }

    private static JsonReader openJson(FileChannel plaintext) throws IOException {
        InputStream input = AttachmentJsonInput.openRange(plaintext, 0, plaintext.size());
        return new JsonReader(new InputStreamReader(new AttachmentJsonInput(input), StandardCharsets.UTF_8));
    }

    public static RestoreSummary restore(Context context, File plaintext) throws Exception {
        return restore(context, plaintext, true, true);
    }

    public static RestoreSummary restore(Context context, File plaintext,
            boolean includeSms, boolean includeCalls) throws Exception {
        return restore(context, plaintext, includeSms, includeCalls, includeSms, includeSms);
    }

    public static RestoreSummary restore(Context context, File plaintext,
            boolean includeSms, boolean includeCalls, boolean includeMms, boolean includeRcs) throws Exception {
        return restore(context, plaintext, includeSms, includeCalls, includeMms, includeRcs, null);
    }

    public static RestoreSummary restore(Context context, File plaintext,
            boolean includeSms, boolean includeCalls, boolean includeMms, boolean includeRcs,
            ArchiveSummary preview) throws Exception {
        return restore(context, plaintext, includeSms, includeCalls, includeMms, includeRcs, preview, null);
    }

    public static RestoreSummary restore(Context context, File plaintext,
            boolean includeSms, boolean includeCalls, boolean includeMms, boolean includeRcs,
            ArchiveSummary preview, ProgressListener listener) throws Exception {
        try (FileInputStream input = new FileInputStream(plaintext)) {
            return restore(context, input.getChannel(), includeSms, includeCalls, includeMms, includeRcs,
                    preview, listener);
        }
    }

    /** Reads the authenticated payload without closing or changing the position of the caller's channel. */
    public static RestoreSummary restore(Context context, FileChannel plaintext,
            boolean includeSms, boolean includeCalls, boolean includeMms, boolean includeRcs,
            ArchiveSummary preview, ProgressListener listener) throws Exception {
        if (preview == null) preview = inspect(plaintext);
        try (JsonReader reader = openJson(plaintext)) {
            return restore(context, reader, plaintext, includeSms, includeCalls, includeMms, includeRcs,
                    preview, listener);
        }
    }

    private static RestoreSummary restore(Context context, JsonReader reader, FileChannel plaintext,
            boolean includeSms, boolean includeCalls, boolean includeMms, boolean includeRcs,
            ArchiveSummary preview, ProgressListener listener) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        boolean hasRcs = includeRcs && preview.rcsCount > 0;
        Set<String> existingSms = (includeSms || includeRcs)
                ? existingKeys(resolver, Telephony.Sms.CONTENT_URI, SMS_KEY_COLUMNS,
                hasRcs ? Long.MIN_VALUE : preview.smsMinDate,
                hasRcs ? Long.MAX_VALUE : preview.smsMaxDate, true) : null;
        Set<String> existingCalls = includeCalls
                ? existingKeys(resolver, CallLog.Calls.CONTENT_URI, CALL_KEY_COLUMNS,
                preview.callsMinDate, preview.callsMaxDate, false) : null;
        Map<String, Long> threadIds = new HashMap<>();
        int total = (includeSms ? preview.smsCount : 0) + (includeCalls ? preview.callsCount : 0)
                + (includeMms ? preview.mmsCount : 0) + (includeRcs ? preview.rcsCount : 0);
        int[] completed = {0};
        reportProgress(listener, completed[0], total);
        boolean mmsProviderAvailable = true;
        Map<String, Integer> existingMms = new HashMap<>();
        if (includeMms && preview.mmsCount > 0) {
            try {
                existingMms = existingMmsKeys(resolver);
            } catch (IOException unavailable) {
                mmsProviderAvailable = false;
            }
        }
        RestoreSummary result = new RestoreSummary();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("sms".equals(name)) {
                if (includeSms) restoreArray(reader, resolver, Telephony.Sms.CONTENT_URI, true, existingSms, result,
                        threadIds, listener, completed, total);
                else reader.skipValue();
            } else if ("calls".equals(name)) {
                if (includeCalls) restoreArray(reader, resolver, CallLog.Calls.CONTENT_URI, false, existingCalls, result,
                        null, listener, completed, total);
                else reader.skipValue();
            } else if ("mms".equals(name)) {
                if (includeMms) restoreMmsSection(context, reader, plaintext, existingMms, result, mmsProviderAvailable,
                        listener, completed, total);
                else reader.skipValue();
            } else if ("rcs".equals(name)) {
                if (includeRcs) restoreRcsSection(reader, resolver, existingSms, result,
                        threadIds, listener, completed, total);
                else reader.skipValue();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return result;
    }

    private static int countArray(JsonReader reader) throws IOException {
        int count = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            reader.skipValue();
            if (count < Integer.MAX_VALUE) count++;
        }
        reader.endArray();
        return count;
    }

    private static int countRecords(JsonReader reader, ArchiveSummary summary, boolean sms) throws IOException {
        int count = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            Long date = null;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("date".equals(name)) {
                    if (reader.peek() == JsonToken.NUMBER) date = reader.nextLong();
                    else if (reader.peek() == JsonToken.STRING) date = parseDate(reader.nextString());
                    else reader.skipValue();
                } else reader.skipValue();
            }
            reader.endObject();
            if (date != null) {
                if (sms) {
                    summary.smsMinDate = Math.min(summary.smsMinDate, date);
                    summary.smsMaxDate = Math.max(summary.smsMaxDate, date);
                } else {
                    summary.callsMinDate = Math.min(summary.callsMinDate, date);
                    summary.callsMaxDate = Math.max(summary.callsMaxDate, date);
                }
            }
            if (count < Integer.MAX_VALUE) count++;
        }
        reader.endArray();
        return count;
    }

    private static int countNestedArray(JsonReader reader, String arrayName) throws IOException {
        int count = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (arrayName.equals(name)) count = countArray(reader);
            else reader.skipValue();
        }
        reader.endObject();
        return count;
    }

    private static void restoreArray(JsonReader reader, ContentResolver resolver, Uri uri,
            boolean sms, Set<String> existing, RestoreSummary result,
            Map<String, Long> threadIds,
            ProgressListener listener, int[] completed, int total) throws IOException {
        MessageDigest digest = sms ? smsKeyDigest() : null;
        reader.beginArray();
        while (reader.hasNext()) {
            ContentValues values = readRecord(reader, sms);
            String key = keyFor(values, sms, digest);
            if (existing.contains(key)) {
                result.duplicates++;
                reportProgress(listener, ++completed[0], total);
                continue;
            }
            try {
                if (sms) {
                    attachThreadId(resolver, values, threadIds);
                }
                Uri inserted = resolver.insert(uri, values);
                if (inserted == null) throw new SecurityException("provider rejected record");
                existing.add(key);
                if (sms) result.smsAdded++; else result.callsAdded++;
            } catch (Exception firstFailure) {
                // Providers can reject provider-specific columns on another Android version. A
                // second attempt with the stable columns is safer than dropping the whole record.
                ContentValues minimal = minimalValues(values, sms);
                try {
                    Uri inserted = resolver.insert(uri, minimal);
                    if (inserted == null) throw new SecurityException("provider rejected record");
                    existing.add(key);
                    if (sms) result.smsAdded++; else result.callsAdded++;
                } catch (Exception ignored) {
                    result.failed++;
                }
            }
            reportProgress(listener, ++completed[0], total);
        }
        reader.endArray();
    }

    private static ContentValues readRecord(JsonReader reader, boolean sms) throws IOException {
        ContentValues values = new ContentValues();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue; }
            // Provider IDs and thread IDs are local to a device and must never be restored.
            if (isNonPortableColumn(name) || (sms && !isRestorableSmsColumn(name))) {
                reader.skipValue();
            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                readEncodedValue(reader, values, name);
            } else if (isLongField(name, sms)) {
                if (reader.peek() == JsonToken.NUMBER) values.put(name, reader.nextLong());
                else values.put(name, parseLong(reader.nextString()));
            } else if (reader.peek() == JsonToken.NUMBER) {
                String raw = reader.nextString();
                try { values.put(name, Long.parseLong(raw)); }
                catch (NumberFormatException ignored) { values.put(name, Double.parseDouble(raw)); }
            } else {
                values.put(name, reader.nextString());
            }
        }
        reader.endObject();
        return values;
    }

    private static void readEncodedValue(JsonReader reader, ContentValues values, String name) throws IOException {
        reader.beginObject();
        String encoding = null, data = null;
        while (reader.hasNext()) {
            String child = reader.nextName();
            if ("encoding".equals(child)) encoding = reader.nextString();
            else if ("data".equals(child)) data = reader.nextString();
            else reader.skipValue();
        }
        reader.endObject();
        if ("base64".equals(encoding) && data != null) values.put(name, android.util.Base64.decode(data, android.util.Base64.DEFAULT));
    }

    private static boolean isNonPortableColumn(String name) {
        return "_id".equals(name) || "thread_id".equals(name) || "person".equals(name)
                || "contact_id".equals(name) || "rcsdb_id".equals(name) || "_data".equals(name)
                || "sub_id".equals(name) || "phone_id".equals(name) || "index_on_icc".equals(name)
                || "raw_contact_id".equals(name);
    }

    private static boolean isRestorableSmsColumn(String name) {
        return "address".equals(name) || "date".equals(name) || "date_sent".equals(name)
                || "read".equals(name) || "seen".equals(name) || "type".equals(name)
                || "body".equals(name) || "subject".equals(name) || "service_center".equals(name)
                || "reply_path_present".equals(name) || "locked".equals(name)
                || "status".equals(name) || "error_code".equals(name);
    }

    private static boolean isLongField(String name, boolean sms) {
        if (sms) return "date".equals(name) || "date_sent".equals(name) || "read".equals(name)
                || "status".equals(name) || "type".equals(name) || "reply_path_present".equals(name)
                || "locked".equals(name) || "error_code".equals(name) || "seen".equals(name)
                || "sub_id".equals(name) || "phone_id".equals(name) || "index_on_icc".equals(name)
                || "priority".equals(name) || "safe_message".equals(name) || "secure_message".equals(name);
        return "date".equals(name) || "duration".equals(name) || "type".equals(name)
                || "new".equals(name) || "numbertype".equals(name) || "is_read".equals(name)
                || "presentation".equals(name) || "features".equals(name) || "data_usage".equals(name)
                || "transcription_state".equals(name) || "block_reason".equals(name)
                || "missed_reason".equals(name);
    }

    private static ContentValues minimalValues(ContentValues source, boolean sms) {
        ContentValues values = new ContentValues();
        if (sms) {
            copy(source, values, "address"); copy(source, values, "date"); copy(source, values, "date_sent");
            copy(source, values, "body"); copy(source, values, "type"); copy(source, values, "read");
            copy(source, values, "seen"); copy(source, values, "status"); copy(source, values, "subject");
            copy(source, values, "service_center"); copy(source, values, "reply_path_present");
            copy(source, values, "locked"); copy(source, values, "error_code"); copy(source, values, "thread_id");
        } else {
            copy(source, values, "number"); copy(source, values, "date"); copy(source, values, "duration");
            copy(source, values, "type"); copy(source, values, "new"); copy(source, values, "name");
        }
        return values;
    }

    private static void copy(ContentValues from, ContentValues to, String key) {
        if (!from.containsKey(key)) return;
        if (from.get(key) instanceof String) to.put(key, from.getAsString(key));
        else to.put(key, from.getAsLong(key));
    }

    private static void attachThreadId(ContentResolver resolver, ContentValues values,
            Map<String, Long> threadIds) {
        if (threadIds == null || values.containsKey("thread_id")) return;
        String address = values.getAsString("address");
        if (address == null || address.isEmpty()) return;
        if (threadIds.containsKey(address)) {
            Long threadId = threadIds.get(address);
            if (threadId != null) values.put("thread_id", threadId);
            return;
        }
        Long threadId = null;
        try {
            Uri threadUri = Uri.parse("content://mms-sms/threadID").buildUpon()
                    .appendQueryParameter("recipient", address).build();
            try (Cursor cursor = resolver.query(threadUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex("_id");
                    if (index < 0) index = cursor.getColumnIndex("thread_id");
                    if (index >= 0 && !cursor.isNull(index)) threadId = cursor.getLong(index);
                }
            }
        } catch (Exception ignored) { }
        threadIds.put(address, threadId);
        if (threadId != null) values.put("thread_id", threadId);
    }

    private static void restoreMmsSection(Context context, JsonReader reader, FileChannel plaintext,
            Map<String, Integer> existing, RestoreSummary result, boolean providerAvailable,
            ProgressListener listener, int[] completed, int total) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("messages".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    if (providerAvailable) restoreMmsMessage(context, reader, plaintext, existing, result);
                    else { reader.skipValue(); result.failed++; }
                    reportProgress(listener, ++completed[0], total);
                }
                reader.endArray();
            } else reader.skipValue();
        }
        reader.endObject();
    }

    private static void restoreMmsMessage(Context context, JsonReader reader, FileChannel plaintext,
            Map<String, Integer> existing, RestoreSummary result) throws IOException {
        ContentValues message = new ContentValues();
        List<MmsPart> parts = new ArrayList<>();
        List<ContentValues> addresses = new ArrayList<>();
        Long dateMs = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("row".equals(name)) message.putAll(readRecord(reader, false));
            else if ("dateMs".equals(name)) dateMs = reader.nextLong();
            else if ("parts".equals(name)) readMmsParts(reader, parts);
            else if ("addresses".equals(name)) readMmsAddresses(reader, addresses);
            else reader.skipValue();
        }
        reader.endObject();
        if (dateMs != null) message.put("date", dateMs / 1000L);
        if (message.containsKey("ct")) {
            if (!message.containsKey(Telephony.Mms.CONTENT_TYPE))
                message.put(Telephony.Mms.CONTENT_TYPE, message.getAsString("ct"));
            message.remove("ct"); // "ct" is the parts column; the MMS row uses "ct_t".
        }
        String key = mmsKey(message);
        Integer remainingMatches = existing.get(key);
        if (remainingMatches != null) {
            if (remainingMatches == 1) existing.remove(key);
            else existing.put(key, remainingMatches - 1);
            result.duplicates++;
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        Uri inserted = null;
        try {
            attachMmsThread(context, message, addresses);
            inserted = resolver.insert(Telephony.Mms.CONTENT_URI, message);
            if (inserted == null) throw new SecurityException("provider rejected MMS");
            String id = inserted.getLastPathSegment();
            for (ContentValues address : addresses) {
                address.put("msg_id", Long.parseLong(id));
                if (resolver.insert(Uri.parse("content://mms/" + id + "/addr"), address) == null)
                    throw new SecurityException("provider rejected MMS address");
            }
            for (MmsPart part : parts) {
                part.values.put("mid", Long.parseLong(id));
                Uri partUri = resolver.insert(Uri.parse("content://mms/" + id + "/part"), part.values);
                if (partUri == null) throw new SecurityException("provider rejected MMS part");
                if (part.dataRange != null) copyMmsData(plaintext, part.dataRange, resolver, partUri);
            }
            result.mmsAdded++;
        } catch (Exception error) {
            if (inserted != null) try { resolver.delete(inserted, null, null); } catch (Exception ignored) { }
            result.failed++;
        }
    }

    private static void attachMmsThread(Context context, ContentValues message, List<ContentValues> addresses) {
        Integer box = message.getAsInteger(Telephony.Mms.MESSAGE_BOX);
        boolean incoming = box != null && box == Telephony.Mms.MESSAGE_BOX_INBOX;
        Set<String> recipients = new HashSet<>();
        Set<String> incomingTo = new HashSet<>();
        boolean copyRecipient = false;
        for (ContentValues row : addresses) {
            Integer type = row.getAsInteger(Telephony.Mms.Addr.TYPE);
            String address = row.getAsString(Telephony.Mms.Addr.ADDRESS);
            if (type == null || address == null || address.isEmpty() || "insert-address-token".equals(address)) continue;
            if (incoming) {
                if (type == MMS_FROM) recipients.add(address);
                else if (type == MMS_TO) incomingTo.add(address);
                else if (type == MMS_CC) copyRecipient = true;
            } else if (type == MMS_TO || type == MMS_CC) {
                recipients.add(address);
            }
        }
        // Legacy archives do not identify the source phone's own number in a group.
        // Preserve the addresses without guessing a sender-only thread for a known group.
        if (incoming && (incomingTo.size() > 1 || copyRecipient)) return;
        if (recipients.isEmpty()) return;
        try {
            message.put(Telephony.Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(context, recipients));
        } catch (IllegalArgumentException | SecurityException ignored) { }
    }

    private static void readMmsParts(JsonReader reader, List<MmsPart> parts) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            ContentValues values = new ContentValues();
            String data = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("row".equals(name)) values.putAll(readRecord(reader, false));
                else if ("dataBase64".equals(name)) data = reader.nextString();
                else reader.skipValue();
            }
            reader.endObject();
            parts.add(new MmsPart(values, data));
        }
        reader.endArray();
    }

    private static void copyMmsData(FileChannel plaintext, String range, ContentResolver resolver, Uri partUri)
            throws IOException {
        int separator = range.indexOf(':');
        if (separator < 0) throw new IOException("invalid MMS attachment range");
        long offset = Long.parseLong(range.substring(0, separator));
        long length = Long.parseLong(range.substring(separator + 1));
        long size = plaintext.size();
        if (offset < 0 || length < 0 || offset > size || length > size - offset)
            throw new IOException("invalid MMS attachment range");
        byte[] encodedBuffer = new byte[32 * 1024]; // Keep complete Base64 quartets between chunks.
        byte[] decodedBuffer = new byte[24 * 1024];
        java.util.Base64.Decoder decoder = java.util.Base64.getDecoder();
        try (InputStream encoded = AttachmentJsonInput.openBase64(plaintext, offset, length);
             OutputStream output = resolver.openOutputStream(partUri)) {
            if (output == null) throw new IOException("cannot open MMS part");
            int remaining = MAX_MMS_PART_BYTES;
            while (true) {
                int lengthRead = 0;
                while (lengthRead < encodedBuffer.length) {
                    int next = encoded.read();
                    if (next == -1) break;
                    encodedBuffer[lengthRead++] = (byte) next;
                }
                if (lengthRead == 0) break;
                boolean last = lengthRead < encodedBuffer.length || encodedBuffer[lengthRead - 1] == '=';
                byte[] block = lengthRead == encodedBuffer.length ? encodedBuffer
                        : Arrays.copyOf(encodedBuffer, lengthRead);
                int count;
                try {
                    count = decoder.decode(block, decodedBuffer);
                } finally {
                    if (block != encodedBuffer) Arrays.fill(block, (byte) 0);
                }
                if (last && encoded.read() != -1) throw new IOException("invalid MMS attachment padding");
                if (count > remaining) throw new IOException("MMS part too large");
                output.write(decodedBuffer, 0, count);
                remaining -= count;
                if (last) break;
            }
        } finally {
            Arrays.fill(encodedBuffer, (byte) 0);
            Arrays.fill(decodedBuffer, (byte) 0);
        }
    }

    private static void readMmsAddresses(JsonReader reader, List<ContentValues> addresses) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("row".equals(name)) addresses.add(readRecord(reader, false));
                else reader.skipValue();
            }
            reader.endObject();
        }
        reader.endArray();
    }

    private static void restoreRcsSection(JsonReader reader, ContentResolver resolver,
            Set<String> existingSms, RestoreSummary result,
            Map<String, Long> threadIds,
            ProgressListener listener, int[] completed, int total) throws IOException {
        MessageDigest digest = smsKeyDigest();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("chats".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    ContentValues row = new ContentValues();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String child = reader.nextName();
                        if ("row".equals(child)) row.putAll(readRecord(reader, false));
                        else reader.skipValue();
                    }
                    reader.endObject();
                    String body = firstString(row, "body", "text", "message", "content");
                    String address = firstString(row, "address", "remote_uri", "from", "to", "number");
                    if (body == null || address == null) { result.failed++; continue; }
                    ContentValues sms = new ContentValues();
                    sms.put("address", address);
                    sms.put("body", body);
                    Long storedDate = row.getAsLong("date");
                    if (storedDate == null) storedDate = row.getAsLong("timestamp");
                    if (storedDate == null) storedDate = row.getAsLong("inserted_timestamp");
                    long date = storedDate == null ? System.currentTimeMillis() : storedDate;
                    sms.put("date", date < 10_000_000_000L ? date * 1000L : date);
                    sms.put("read", row.getAsInteger("read") == null ? 1 : row.getAsInteger("read"));
                    sms.put("seen", row.getAsInteger("seen") == null ? 1 : row.getAsInteger("seen"));
                    sms.put("type", row.getAsInteger("type") == null ? Telephony.Sms.MESSAGE_TYPE_INBOX : row.getAsInteger("type"));
                    attachThreadId(resolver, sms, threadIds);
                    String key = keyFor(sms, true, digest);
                    if (existingSms.contains(key)) { result.duplicates++; continue; }
                    try {
                        if (resolver.insert(Telephony.Sms.CONTENT_URI, sms) == null) throw new SecurityException("provider rejected RCS fallback");
                        existingSms.add(key); result.rcsAdded++;
                    } catch (Exception error) { result.failed++; }
                    reportProgress(listener, ++completed[0], total);
                }
                reader.endArray();
            } else reader.skipValue();
        }
        reader.endObject();
    }

    private static String firstString(ContentValues values, String... names) {
        for (String name : names) {
            String value = values.getAsString(name);
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    private static void reportProgress(ProgressListener listener, int completed, int total) {
        if (listener != null) listener.onProgress(completed, total);
    }

    private static Map<String, Integer> existingMmsKeys(ContentResolver resolver) throws IOException {
        Map<String, Integer> keys = new HashMap<>();
        try (Cursor cursor = resolver.query(Telephony.Mms.CONTENT_URI, MMS_KEY_COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("MMS provider unavailable");
            while (cursor.moveToNext()) {
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < cursor.getColumnCount(); i++) key.append(cursor.isNull(i) ? "<null>" : cursor.getString(i)).append('\u001f');
                keys.merge(key.toString(), 1, Integer::sum);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MMS provider unavailable", e);
        }
        return keys;
    }

    private static String mmsKey(ContentValues values) {
        StringBuilder key = new StringBuilder();
        for (String name : MMS_KEY_COLUMNS)
            key.append(values.getAsString(name) == null ? "<null>" : values.getAsString(name)).append('\u001f');
        return key.toString();
    }

    private static final class MmsPart {
        final ContentValues values;
        final String dataRange;
        MmsPart(ContentValues values, String dataRange) { this.values = values; this.dataRange = dataRange; }
    }

    private static Set<String> existingKeys(ContentResolver resolver, Uri uri, String[] columns,
            long minDate, long maxDate, boolean sms) throws IOException {
        Set<String> keys = new HashSet<>();
        MessageDigest digest = sms ? smsKeyDigest() : null;
        String selection = minDate != Long.MAX_VALUE && maxDate != Long.MIN_VALUE
                ? "date >= ? AND date <= ?" : null;
        String[] selectionArgs = selection == null ? null
                : new String[]{String.valueOf(minDate), String.valueOf(maxDate)};
        try (Cursor cursor = resolver.query(uri, columns, selection, selectionArgs, null)) {
            if (cursor == null) throw new IOException("provider unavailable");
            while (cursor.moveToNext()) {
                StringBuilder key = new StringBuilder();
                for (String column : columns) {
                    int index = cursor.getColumnIndex(column);
                    if (index < 0 || cursor.isNull(index)) key.append("<null>");
                    else key.append(cursor.getString(index));
                    key.append('\u001f');
                }
                keys.add(compactKey(key, digest));
            }
        } catch (SecurityException e) {
            throw new IOException("read permission required", e);
        }
        return keys;
    }

    private static String keyFor(ContentValues values, boolean sms, MessageDigest digest) {
        String[] columns = sms ? SMS_KEY_COLUMNS : CALL_KEY_COLUMNS;
        StringBuilder key = new StringBuilder();
        for (String column : columns) {
            String value = values.getAsString(column);
            key.append(value == null ? "<null>" : value).append('\u001f');
        }
        return compactKey(key, digest);
    }

    // Don't keep a second copy of every SMS body in memory during large restores.
    private static String compactKey(StringBuilder key, MessageDigest digest) {
        String value = key.toString();
        if (digest == null) return value;
        return Base64.encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static MessageDigest smsKeyDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; }
    }

    private static Long parseDate(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return null; }
    }

    public static final class RestoreSummary {
        public int smsAdded;
        public int callsAdded;
        public int mmsAdded;
        public int rcsAdded;
        public int duplicates;
        public int failed;
    }

    public static final class ArchiveSummary {
        public int smsCount;
        public int callsCount;
        public int mmsCount;
        public int rcsCount;
        long smsMinDate = Long.MAX_VALUE;
        long smsMaxDate = Long.MIN_VALUE;
        long callsMinDate = Long.MAX_VALUE;
        long callsMaxDate = Long.MIN_VALUE;
    }
}
