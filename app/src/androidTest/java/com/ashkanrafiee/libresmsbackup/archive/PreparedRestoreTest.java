package com.ashkanrafiee.libresmsbackup.archive;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import junit.framework.TestCase;

public class PreparedRestoreTest extends TestCase {
    private static final byte[] PAYLOAD = "{\"schema\":2,\"sms\":[{}],\"calls\":[]}"
            .getBytes(StandardCharsets.UTF_8);

    public void testPreviewCountsPlainAndEncryptedArchives() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int before = countStaged(context);
        for (boolean encrypted : new boolean[]{false, true}) {
            File archive = File.createTempFile("preview-test-", ".lsb", context.getCacheDir());
            try {
                writeArchive(archive, encrypted);
                char[] password = encrypted ? password() : new char[0];
                try (ArchiveFormat.PreparedRestore prepared =
                             ArchiveFormat.prepareRestore(context, Uri.fromFile(archive), password)) {
                    assertEquals(encrypted, prepared.encrypted);
                    assertEquals(1, prepared.summary.smsCount);
                    assertEquals(0, prepared.summary.callsCount);
                    assertEquals(before, countStaged(context));
                }
                assertTrue(Arrays.equals(password, new char[password.length]));
            } finally {
                archive.delete();
            }
        }
    }

    public void testLargeLegacyEncryptedArchiveRestoresSelectedCallsOnce() throws Exception {
        // This test uses the streaming provider and a wrapped in-process call-log provider.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestCallLog callLog = new TestCallLog();
        ContentResolver resolver = ContentResolver.wrap(callLog);
        Context restoreContext = new ContextWrapper(context) {
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        File archive = File.createTempFile("preview-test-", ".lsb.enc", context.getCacheDir());
        try {
            writeLargeLegacyArchive(archive);
            int before = countStaged(context);
            char[] password = password();
            try (ArchiveFormat.PreparedRestore prepared =
                         ArchiveFormat.prepareRestore(context, Uri.fromFile(archive), password)) {
                assertTrue(prepared.encrypted);
                assertEquals(1, prepared.summary.smsCount);
                assertEquals(1, prepared.summary.callsCount);
                assertEquals(before, countStaged(context));
                assertTrue(callLog.rows.isEmpty());
                assertEquals(1, prepared.restore(restoreContext, false, true).callsAdded);
                assertEquals(1, prepared.restore(restoreContext, false, true).duplicates);
                assertEquals(1, callLog.rows.size());
                assertEquals("5550100", callLog.rows.get(0).getAsString("number"));
            }
            assertEquals(before, countStaged(context));
            assertTrue(Arrays.equals(password, new char[password.length]));
        } finally {
            archive.delete();
        }
    }

    public void testTamperedArchiveDoesNotLeaveAStagedPayload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("preview-test-", ".lsb", context.getCacheDir());
        try {
            writeArchive(archive, true);
            try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) {
                long last = file.length() - 1;
                file.seek(last);
                int tagByte = file.readUnsignedByte();
                file.seek(last);
                file.write(tagByte ^ 1);
            }
            int before = countStaged(context);
            char[] password = password();
            try (ArchiveFormat.PreparedRestore ignored =
                         ArchiveFormat.prepareRestore(context, Uri.fromFile(archive), password)) {
                fail("a tampered archive must not be available for restore");
            } catch (java.io.IOException expected) {
                assertTrue(ArchiveCrypto.isAuthenticationFailure(expected));
            }
            assertEquals(before, countStaged(context));
            assertTrue(Arrays.equals(password, new char[password.length]));
        } finally {
            archive.delete();
        }
    }

    private static int countStaged(Context context) {
        String[] files = context.getCacheDir().list((dir, name) -> name.startsWith("libresmsbackup-restore-"));
        assertNotNull(files);
        return files.length;
    }

    private static char[] password() {
        return "preview test password".toCharArray();
    }

    private static void writeArchive(File archive, boolean encrypted) throws Exception {
        try (OutputStream raw = new FileOutputStream(archive);
             OutputStream output = ArchiveCrypto.openOutput(raw, null, password(), encrypted)) {
            output.write(PAYLOAD);
        }
    }

    // Build an LSMBK002 fixture independently of the current writer and its heap-based cap.
    private static void writeLargeLegacyArchive(File archive) throws Exception {
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
        byte[] padding = new byte[32 * 1024];
        Arrays.fill(padding, (byte) ' ');
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(archive))) {
            output.writeBytes("LSMBK002");
            output.writeByte(2);
            output.writeInt(header.length);
            output.write(header);
            output.write(cipher.update("{\"schema\":2,\"sms\":[{}],".getBytes(StandardCharsets.UTF_8)));
            for (int chunk = 0; chunk < 78 * 32; chunk++) output.write(cipher.update(padding));
            output.write(cipher.doFinal(("\"calls\":[{\"number\":\"5550100\",\"date\":1700000000000,"
                    + "\"duration\":57,\"type\":1}]}").getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static final class TestCallLog extends ContentProvider {
        final List<ContentValues> rows = new ArrayList<>();

        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                String order) {
            assertEquals(CallLog.Calls.CONTENT_URI, uri);
            MatrixCursor cursor = new MatrixCursor(projection);
            for (ContentValues values : rows) {
                MatrixCursor.RowBuilder row = cursor.newRow();
                for (String column : projection) row.add(values.get(column));
            }
            return cursor;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            assertEquals(CallLog.Calls.CONTENT_URI, uri);
            rows.add(new ContentValues(values));
            return Uri.withAppendedPath(uri, String.valueOf(rows.size()));
        }

        @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
