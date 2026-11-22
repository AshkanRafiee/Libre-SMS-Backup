package com.ashkanrafiee.libresmsbackup.archive;

import android.content.Context;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import junit.framework.TestCase;

public class PreparedRestoreTest extends TestCase {
    private static final byte[] PAYLOAD = "{\"schema\":2,\"sms\":[{}],\"calls\":[]}"
            .getBytes(StandardCharsets.UTF_8);

    public void testPreviewCountsPlainAndEncryptedArchives() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
                }
                assertTrue(Arrays.equals(password, new char[password.length]));
            } finally {
                archive.delete();
            }
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
}
