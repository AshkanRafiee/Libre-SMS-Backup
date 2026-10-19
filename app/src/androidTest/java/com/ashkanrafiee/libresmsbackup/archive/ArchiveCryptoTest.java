package com.ashkanrafiee.libresmsbackup.archive;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import junit.framework.TestCase;

/** Format-level tests that do not touch the SMS or call-log providers. */
public class ArchiveCryptoTest extends TestCase {
    public void testRoundTripAuthenticatesPayload() throws Exception {
        byte[] original = "private records stay authenticated".getBytes(StandardCharsets.UTF_8);
        byte[] archive = ArchiveCrypto.encryptForTest(original, "correct horse battery staple".toCharArray());
        byte[] restored = ArchiveCrypto.decryptForTest(archive, "correct horse battery staple".toCharArray());
        assertTrue(Arrays.equals(original, restored));
    }

    public void testWrongPasswordAndTamperingAreRejected() throws Exception {
        byte[] archive = ArchiveCrypto.encryptForTest(new byte[] {1, 2, 3, 4}, "password-123".toCharArray());
        try {
            ArchiveCrypto.decryptForTest(archive, "password-124".toCharArray());
            fail("wrong password must fail authentication");
        } catch (Exception expected) { assertTrue(ArchiveCrypto.isAuthenticationFailure(expected)); }

        archive[archive.length - 1] ^= 1;
        try {
            ArchiveCrypto.decryptForTest(archive, "password-123".toCharArray());
            fail("tampering must fail authentication");
        } catch (Exception expected) { assertTrue(ArchiveCrypto.isAuthenticationFailure(expected)); }
    }

    public void testExplicitlyUnencryptedArchiveCanBeReadWithoutPassword() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OutputStream payload = ArchiveCrypto.openOutput(output, null, null, false);
        payload.write("readable test archive".getBytes(StandardCharsets.UTF_8));
        payload.close();
        InputStream input = ArchiveCrypto.openDecrypted(new ByteArrayInputStream(output.toByteArray()), new char[0]);
        ByteArrayOutputStream restoredOutput = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int count; while ((count = input.read(buffer)) != -1) restoredOutput.write(buffer, 0, count);
        input.close();
        assertEquals("readable test archive", new String(restoredOutput.toByteArray(), StandardCharsets.UTF_8));
    }

    public void testHeaderReportsWhetherPasswordIsRequired() throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        OutputStream plainPayload = ArchiveCrypto.openOutput(plain, null, null, false);
        plainPayload.close();
        assertFalse(ArchiveCrypto.requiresPassword(new ByteArrayInputStream(plain.toByteArray())));

        byte[] encrypted = ArchiveCrypto.encryptForTest(new byte[] {1}, "password-123".toCharArray());
        assertTrue(ArchiveCrypto.requiresPassword(new ByteArrayInputStream(encrypted)));
    }
}
