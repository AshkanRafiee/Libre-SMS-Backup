package com.ashkanrafiee.libresmsbackup.data;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Telephony;

import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.libresmsbackup.archive.ArchiveFormat;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import junit.framework.TestCase;

public class LargeMmsRestoreTest extends TestCase {
    private static final int LARGE_BYTES = 57 * 1024 * 1024 + 1;
    private static final int SMALL_BYTES = 1536 * 1024 + 2;
    private static final Uri MESSAGE_URI = Uri.parse("content://mms/42");
    private static final String MMS_START =
            "{\"schema\":2,\"sms\":[],\"calls\":[],\"mms\":{\"messages\":[{\"parts\":[";
    private static final String MMS_END =
            "],\"dateMs\":1700000000123,\"row\":{\"date\":1,\"msg_box\":1,\"sub\":\"Attachments\","
                    + "\"ct\":\"application/vnd.wap.multipart.related\"},"
                    + "\"addresses\":[{\"row\":{\"address\":\"+15550100\",\"type\":137}}]}],"
                    + "\"status\":\"available\"}}";

    public void testLargeLegacyEncryptedMmsRestoresOnce() throws Exception {
        // ContentResolver.wrap and explicit platform BC access require Android 12 for this test.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("large-mms-", ".lsb.enc", context.getCacheDir());
        try (TestMmsProvider provider = new TestMmsProvider(context)) {
            byte[][] expectedDigests = writeLargeArchive(archive);
            try (ArchiveFormat.PreparedRestore prepared = ArchiveFormat.prepareRestore(
                    context, Uri.fromFile(archive), password())) {
                assertTrue(prepared.encrypted);
                assertEquals(0, prepared.summary.smsCount);
                assertEquals(1, prepared.summary.mmsCount);
                assertNull(provider.message);
                // The second pass must read the authenticated stage even without the source archive.
                assertTrue(archive.delete());

                Context restoreContext = provider.wrap(context);
                RestoreEngine.RestoreSummary first = prepared.restore(restoreContext, true, false);
                assertEquals(1, first.mmsAdded);
                assertEquals(0, first.failed);
                assertEquals(0, first.duplicates);
                assertEquals(Long.valueOf(1700000000L), provider.message.getAsLong("date"));
                assertEquals(Long.valueOf(7), provider.message.getAsLong(Telephony.Mms.THREAD_ID));
                assertEquals(1, provider.addresses.size());
                assertEquals("+15550100", provider.addresses.get(0).getAsString("address"));
                assertEquals(Long.valueOf(42), provider.addresses.get(0).getAsLong("msg_id"));
                assertEquals(2, provider.parts.size());
                assertEquals("large.bin", provider.parts.get(0).getAsString("name"));
                assertEquals("small.bin", provider.parts.get(1).getAsString("name"));
                for (ContentValues part : provider.parts) {
                    assertEquals(Long.valueOf(42), part.getAsLong("mid"));
                }
                assertPart(provider.partFile(0), LARGE_BYTES, expectedDigests[0]);
                assertPart(provider.partFile(1), SMALL_BYTES, expectedDigests[1]);

                RestoreEngine.RestoreSummary repeat = prepared.restore(restoreContext, true, false);
                assertEquals(0, repeat.mmsAdded);
                assertEquals(0, repeat.failed);
                assertEquals(1, repeat.duplicates);
                assertEquals(2, provider.partOpenCount);
                assertEquals(0, provider.deleteCount);
            }
        } finally {
            archive.delete();
        }
    }

    public void testFailedPartRollsBackMessageAndAllowsRetry() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("rollback-mms-", ".json", context.getCacheDir());
        try (TestMmsProvider provider = new TestMmsProvider(context)) {
            try (OutputStream output = new FileOutputStream(archive)) {
                writeJson(output, MMS_START + "{\"dataBase64\":\"YQ==\"},"
                        + "{\"dataBase64\":\"Yg==\"}" + MMS_END);
            }
            provider.rejectSecondPart = true;
            Context restoreContext = provider.wrap(context);
            RestoreEngine.RestoreSummary failed = RestoreEngine.restore(restoreContext, archive, true, false);
            assertEquals(0, failed.mmsAdded);
            assertEquals(1, failed.failed);
            assertEquals(2, provider.partOpenCount);
            assertEquals(1, provider.deleteCount);
            assertNull(provider.message);
            assertTrue(provider.addresses.isEmpty());
            assertTrue(provider.parts.isEmpty());
            assertFalse(provider.partFile(0).exists());

            provider.rejectSecondPart = false;
            RestoreEngine.RestoreSummary retry = RestoreEngine.restore(restoreContext, archive, true, false);
            assertEquals(1, retry.mmsAdded);
            assertEquals(0, retry.failed);
            assertEquals(0, retry.duplicates);
            assertEquals(4, provider.partOpenCount);
        } finally {
            archive.delete();
        }
    }

    public void testSameSecondMessagesCountSeparatelyAndDeduplicateOnRetry() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("same-second-mms-", ".json", context.getCacheDir());
        try (TestMmsProvider provider = new TestMmsProvider(context)) {
            String base = "{\"row\":{\"date\":1700000000,\"msg_box\":1,\"ct_t\":"
                    + "\"application/vnd.wap.multipart.related\",\"read\":%d},\"parts\":[],\"addresses\":[]}";
            try (OutputStream output = new FileOutputStream(archive)) {
                writeJson(output, "{\"schema\":2,\"mms\":{\"messages\":["
                        + String.format(Locale.ROOT, base, 0) + ","
                        + String.format(Locale.ROOT, base, 1) + "]}}");
            }
            Context restoreContext = provider.wrap(context);
            RestoreEngine.RestoreSummary first = RestoreEngine.restore(restoreContext, archive, true, false);
            assertEquals(2, first.mmsAdded);
            assertEquals(0, first.duplicates);
            RestoreEngine.RestoreSummary repeat = RestoreEngine.restore(restoreContext, archive, true, false);
            assertEquals(0, repeat.mmsAdded);
            assertEquals(2, repeat.duplicates);
        } finally {
            archive.delete();
        }
    }

    public void testLegacyIncomingGroupDoesNotGetSenderOnlyThread() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("group-mms-", ".json", context.getCacheDir());
        try (TestMmsProvider provider = new TestMmsProvider(context)) {
            try (OutputStream output = new FileOutputStream(archive)) {
                writeJson(output, "{\"schema\":2,\"mms\":{\"messages\":[{\"row\":{\"date\":1700000000,"
                        + "\"msg_box\":1},\"addresses\":[{\"row\":{\"address\":\"+15550100\",\"type\":137}},"
                        + "{\"row\":{\"address\":\"+15550101\",\"type\":151}},"
                        + "{\"row\":{\"address\":\"+15550102\",\"type\":151}}]}]}}");
            }
            RestoreEngine.RestoreSummary result = RestoreEngine.restore(provider.wrap(context), archive, true, false);
            assertEquals(1, result.mmsAdded);
            assertEquals(3, provider.addresses.size());
            assertFalse(provider.message.containsKey(Telephony.Mms.THREAD_ID));
        } finally {
            archive.delete();
        }
    }

    // Write the legacy LSMBK002 container independently of the current writer's heap-based cap.
    private static byte[][] writeLargeArchive(File archive) throws Exception {
        byte[] header = ("{\"format\":\"LSMBK\",\"version\":2,\"kdf\":{"
                + "\"algorithm\":\"PBKDF2WithHmacSHA256\",\"iterations\":100000,"
                + "\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"keyBits\":256},"
                + "\"cipher\":{\"algorithm\":\"AES/GCM/NoPadding\","
                + "\"iv\":\"AAAAAAAAAAAAAAAA\",\"tagBits\":128}}").getBytes(StandardCharsets.UTF_8);
        PBEKeySpec spec = new PBEKeySpec(password(), new byte[16], 100_000, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
        cipher.updateAAD(header);
        try (DataOutputStream raw = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            raw.writeBytes("LSMBK002");
            raw.writeByte(2);
            raw.writeInt(header.length);
            raw.write(header);
            try (OutputStream output = new CipherOutputStream(raw, cipher)) {
                writeJson(output, MMS_START + "{\"dataBase64\":\"");
                byte[] large = writePart(output, LARGE_BYTES, 7, false);
                writeJson(output, "\",\"row\":{\"ct\":\"application/octet-stream\",\"name\":\"large.bin\"}},"
                        + "{\"row\":{\"ct\":\"application/octet-stream\",\"name\":\"small.bin\"},"
                        + "\"data\\u0042ase64\":\"");
                byte[] small = writePart(output, SMALL_BYTES, 13, true);
                writeJson(output, "\"}" + MMS_END);
                return new byte[][]{large, small};
            }
        }
    }

    private static byte[] writePart(OutputStream output, int size, int seed, boolean escapedPrefix)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (escapedPrefix) {
            // The first quartet decodes to FE EE DD; the escaped newline is Base64 whitespace.
            writeJson(output, "\\/u7\\u0064\\n");
            digest.update(new byte[]{(byte) 0xfe, (byte) 0xee, (byte) 0xdd});
            size -= 3;
        }
        Random random = new Random(seed);
        Base64.Encoder encoder = Base64.getEncoder();
        byte[] chunk = new byte[24 * 1024]; // Each full chunk encodes without padding.
        byte[] encoded = new byte[32 * 1024];
        while (size >= chunk.length) {
            random.nextBytes(chunk);
            digest.update(chunk);
            output.write(encoded, 0, encoder.encode(chunk, encoded));
            size -= chunk.length;
        }
        random.nextBytes(chunk);
        digest.update(chunk, 0, size);
        output.write(encoder.encode(Arrays.copyOf(chunk, size)));
        return digest.digest();
    }

    private static void writeJson(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static char[] password() {
        return "large MMS test password".toCharArray();
    }

    private static void assertPart(File file, long size, byte[] expectedDigest) throws Exception {
        assertEquals(size, file.length());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        assertTrue("attachment digest differs: " + file.getName(), Arrays.equals(expectedDigest, digest.digest()));
    }

    private static final class TestMmsProvider extends ContentProvider implements AutoCloseable {
        final File outputDir;
        final List<ContentValues> addresses = new ArrayList<>();
        final List<ContentValues> parts = new ArrayList<>();
        final List<ContentValues> messages = new ArrayList<>();
        ContentValues message;
        boolean rejectSecondPart;
        int partOpenCount;
        int deleteCount;

        TestMmsProvider(Context context) throws IOException {
            outputDir = Files.createTempDirectory(context.getCacheDir().toPath(), "restored-mms-").toFile();
        }

        Context wrap(Context context) {
            ContentResolver resolver = ContentResolver.wrap(this);
            return new ContextWrapper(context) {
                @Override public ContentResolver getContentResolver() { return resolver; }
            };
        }

        File partFile(int index) {
            return new File(outputDir, "part-" + index);
        }

        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if ("mms-sms".equals(uri.getAuthority())) {
                assertEquals("/threadID", uri.getPath());
                assertEquals("+15550100", uri.getQueryParameter("recipient"));
                cursor.addRow(new Object[]{7L});
                return cursor;
            }
            if (!Telephony.Sms.CONTENT_URI.equals(uri)) {
                assertEquals(Telephony.Mms.CONTENT_URI, uri);
                for (ContentValues existing : messages) {
                    MatrixCursor.RowBuilder row = cursor.newRow();
                    for (String column : projection) row.add(existing.get(column));
                }
            }
            return cursor;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            if (Telephony.Mms.CONTENT_URI.equals(uri)) {
                message = new ContentValues(values);
                messages.add(message);
                return ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, 41L + messages.size());
            }
            if (Uri.withAppendedPath(MESSAGE_URI, "addr").equals(uri)) {
                addresses.add(new ContentValues(values));
                return Uri.withAppendedPath(uri, String.valueOf(addresses.size()));
            }
            assertEquals(Uri.withAppendedPath(MESSAGE_URI, "part"), uri);
            int index = parts.size();
            parts.add(new ContentValues(values));
            return Uri.parse("content://mms/part/" + index);
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            int index = Integer.parseInt(uri.getLastPathSegment());
            assertEquals(Uri.parse("content://mms/part/" + index), uri);
            assertTrue(index >= 0 && index < parts.size());
            partOpenCount++;
            if (rejectSecondPart && index == 1) throw new FileNotFoundException("test rejects the second part");
            return ParcelFileDescriptor.open(partFile(index), ParcelFileDescriptor.parseMode(mode));
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            assertEquals(MESSAGE_URI, uri);
            assertNotNull(message);
            deleteCount++;
            removePartFiles();
            parts.clear();
            addresses.clear();
            messages.clear();
            message = null;
            return 1;
        }

        @Override public void close() {
            removePartFiles();
            outputDir.delete();
        }

        private void removePartFiles() {
            for (int index = 0; index < parts.size(); index++) partFile(index).delete();
        }

        @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
