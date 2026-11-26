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
import android.os.ParcelFileDescriptor;

import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.libresmsbackup.data.RestoreEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import junit.framework.TestCase;

public class ArchiveWriteLimitsTest extends TestCase {
    private static final Uri DESTINATION = Uri.parse("content://test.archive/output");
    private static final int TOKEN_BYTES = 4 * 1024 * 1024;

    private Context context;
    private TestProvider provider;

    @Override protected void setUp() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        provider = new TestProvider();
        provider.archive = File.createTempFile("archive-limits-", ".lsb", targetContext.getCacheDir());
        ContentResolver resolver = ContentResolver.wrap(provider);
        context = new ContextWrapper(targetContext) {
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
    }

    @Override protected void tearDown() {
        if (provider != null && provider.archive != null) provider.archive.delete();
        if (provider != null && provider.attachment != null) provider.attachment.delete();
    }

    public void testLimitsCountExportedColumnsOnly() throws Exception {
        if (context == null) return;
        provider.sms = wideRow(1024);
        assertEquals(1, create().smsCount);
        assertEquals(1, inspectSmsCount());
        provider.sms = wideRow(1025);
        assertRejected("backup object has too many fields");
    }

    public void testTokenLimitCountsUtf8JsonEscapesAndClosingQuote() throws Exception {
        if (context == null) return;
        // U+2028 uses six escaped bytes, é uses two, 😀 uses four, and the closing quote uses one.
        String atLimit = repeated('a', TOKEN_BYTES - 13) + "\u2028é😀";
        provider.sms = row(new String[]{"body"}, atLimit);
        assertEquals(1, create().smsCount);
        assertEquals(1, inspectSmsCount());
        provider.sms = row(new String[]{"body"}, atLimit + "a");
        assertRejected("backup metadata value is too large");
    }

    public void testInlineBlobMustFitInOneTokenAfterBase64Encoding() throws Exception {
        if (context == null) return;
        provider.sms = row(new String[]{"blob"}, new byte[3 * 1024 * 1024]);
        assertRejected("backup metadata value is too large");
    }

    public void testIndividuallyValidTokensCanExceedSmsRowBudget() throws Exception {
        if (context == null) return;
        String body = repeated('a', TOKEN_BYTES - 1);
        provider.sms = row(new String[]{"body", "extra"}, body, body);
        assertRejected("backup row is too large");
    }

    public void testMmsMetadataBudgetIncludesChildWrappers() throws Exception {
        if (context == null) return;
        // The two encoded rows fit on their own; the MMS wrappers put them over the limit.
        String body = repeated('a', TOKEN_BYTES - "{\"text\":\"\"}".length());
        MatrixCursor parts = new MatrixCursor(new String[]{"text"});
        parts.addRow(new Object[]{body});
        parts.addRow(new Object[]{body});
        provider.mms = true;
        provider.parts = parts;
        assertRejected("MMS metadata is too large");
    }

    public void testMmsCountsPartsAndAddressesTogether() throws Exception {
        if (context == null) return;
        provider.mms = true;
        provider.parts = children("ct", "text/plain", 2048);
        provider.addresses = children("address", "5550100", 2048);
        assertEquals(1, create().mmsCount);
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            assertEquals(1, prepared.summary.mmsCount);
        }
        provider.parts = children("ct", "text/plain", 2048);
        provider.addresses = children("address", "5550100", 2049);
        assertRejected("MMS has too many parts or addresses");
    }

    public void testMmsAttachmentExemptFromMetadataTokenAndByteLimits() throws Exception {
        if (context == null) return;
        provider.mms = true;
        provider.parts = row(new String[]{"_id", "ct"}, 1L, "image/png");
        provider.attachment = File.createTempFile("archive-attachment-", ".bin", context.getCacheDir());
        try (RandomAccessFile file = new RandomAccessFile(provider.attachment, "rw")) {
            file.setLength(64L * 1024 * 1024);
        }
        ArchiveFormat.BackupSummary summary = create();
        assertEquals(1, summary.mmsCount);
        assertEquals(0, summary.omittedAttachments);
        assertTrue(provider.archive.length() > 85L * 1024 * 1024);
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            assertEquals(1, prepared.summary.mmsCount);
        }
        try (RandomAccessFile file = new RandomAccessFile(provider.attachment, "rw")) {
            file.setLength(64L * 1024 * 1024 + 1);
        }
        MatrixCursor parts = new MatrixCursor(new String[]{"_id", "ct", "text", "_data"});
        parts.addRow(new Object[]{1L, "image/png", null, null});
        parts.addRow(new Object[]{2L, "text/plain", "", null});
        parts.addRow(new Object[]{3L, "image/jpeg", null, null});
        parts.addRow(new Object[]{4L, "text/plain", null, null});
        parts.addRow(new Object[]{5L, "text/plain", "", "attachment"});
        provider.parts = parts;
        summary = create();
        assertEquals(4, summary.omittedAttachments);
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            assertEquals(1, prepared.summary.mmsCount);
        }
    }

    public void testMmsExportsResolvedConversationAddressesWithoutLocalIds() throws Exception {
        if (context == null) return;
        provider.mms = true;
        provider.conversation = true;
        provider.parts = new MatrixCursor(new String[]{"ct"});
        assertEquals(1, create().mmsCount);
        String archive = new String(Files.readAllBytes(provider.archive.toPath()), StandardCharsets.UTF_8);
        assertTrue(archive.contains("\"threadRecipients\":[\"2025550101\",\"2025550102\"]"));
        assertFalse(archive.contains("\"thread_id\""));
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            assertEquals(1, prepared.summary.mmsCount);
        }
    }

    public void testUnreadableFileBackedInlineMmsIsNotRestoredAsBlankText() throws Exception {
        if (context == null) return;
        provider.mms = true;
        provider.parts = row(new String[]{"_id", "ct", "text", "_data"}, 2L, "text/plain", "", "provider-file");
        assertEquals(1, create().omittedAttachments);
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            RestoreEngine.RestoreSummary restored = prepared.restore(context, true, false);
            assertEquals(0, restored.mmsAdded);
            assertEquals(1, restored.failed);
            assertEquals(0, provider.inserts);
        }
    }

    public void testEncryptedAttachmentPaddingAndMissingValues() throws Exception {
        if (context == null) return;
        provider.mms = true;
        provider.attachment = File.createTempFile("archive-attachment-", ".bin", context.getCacheDir());
        for (byte[] bytes : new byte[][]{{'a', 'b'}, {}}) {
            Files.write(provider.attachment.toPath(), bytes);
            MatrixCursor parts = new MatrixCursor(new String[]{"_id", "ct"});
            parts.addRow(new Object[]{1L, "application/octet-stream"});
            parts.addRow(new Object[]{2L, "application/octet-stream"});
            provider.parts = parts;
            ArchiveFormat.BackupSummary summary = ArchiveFormat.create(
                    context, DESTINATION, "fixture-pass".toCharArray(), true, true, false);
            assertEquals(1, summary.omittedAttachments);
            ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(provider.archive)) {
                assertTrue(ArchiveCrypto.stagePayload(input, plaintext, "fixture-pass".toCharArray()));
            }
            String json = new String(plaintext.toByteArray(), StandardCharsets.UTF_8);
            String value = bytes.length == 0 ? "" : "YWI=";
            assertTrue(json.contains("\"dataBase64\":\"" + value + "\""));
            assertEquals(json.indexOf("\"dataBase64\""), json.lastIndexOf("\"dataBase64\""));
        }
    }

    public void testUnavailableRcsFileCountsAsOmittedAttachment() throws Exception {
        if (context == null) return;
        provider.rcs = true;
        ArchiveFormat.BackupSummary summary = create();
        assertEquals(1, summary.rcsCount);
        assertEquals(1, summary.omittedAttachments);
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            assertEquals(1, prepared.summary.rcsCount);
        }
    }

    private ArchiveFormat.BackupSummary create() throws Exception {
        return ArchiveFormat.create(context, DESTINATION, null, false, true, false);
    }

    private int inspectSmsCount() throws Exception {
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            return prepared.summary.smsCount;
        }
    }

    private void assertRejected(String message) throws Exception {
        byte[] original = {1, 2, 3};
        Files.write(provider.archive.toPath(), original);
        try {
            create();
            fail("writer published an archive exceeding its restore limits");
        } catch (ArchiveFormat.MetadataLimitException expected) {
            assertEquals(message, expected.getMessage());
        }
        assertTrue(Arrays.equals(original, Files.readAllBytes(provider.archive.toPath())));
    }

    private static MatrixCursor wideRow(int exportedFields) {
        String[] columns = new String[exportedFields + 1];
        columns[0] = "_id";
        for (int i = 1; i < columns.length; i++) columns[i] = "column" + i;
        return row(columns, new Object[columns.length]);
    }

    private static MatrixCursor row(String[] columns, Object... values) {
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }

    private static MatrixCursor children(String field, String value, int count) {
        MatrixCursor cursor = new MatrixCursor(new String[]{field}, count);
        for (int i = 0; i < count; i++) cursor.addRow(new Object[]{value});
        return cursor;
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static final class TestProvider extends ContentProvider {
        File archive;
        File attachment;
        Cursor sms;
        Cursor parts;
        Cursor addresses;
        boolean mms;
        boolean conversation;
        boolean rcs;
        int inserts;

        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                String order) {
            switch (uri.toString()) {
                case "content://sms": return sms == null ? new MatrixCursor(new String[]{"body"}) : sms;
                case "content://mms":
                    if (projection != null) return new MatrixCursor(projection);
                    if (!mms) return null;
                    return conversation ? row(new String[]{"_id", "thread_id"}, 1L, 99L)
                            : row(new String[]{"_id"}, 1L);
                case "content://mms-sms/conversations?simple=true":
                    return row(new String[]{"recipient_ids"}, "100 101");
                case "content://mms-sms/canonical-address/100":
                    return row(new String[]{"address"}, "2025550101");
                case "content://mms-sms/canonical-address/101":
                    return row(new String[]{"address"}, "2025550102");
                case "content://im/chat":
                    return rcs ? row(new String[]{"body", "address"}, "RCS message", "2025550103") : null;
                case "content://im/ft":
                    return rcs ? row(new String[]{"_id"}, 1L) : null;
                case "content://mms/1/part": return parts;
                case "content://mms/1/addr":
                    return addresses == null ? new MatrixCursor(new String[]{"address"}) : addresses;
                default: return null;
            }
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (DESTINATION.equals(uri)) return ParcelFileDescriptor.open(archive, ParcelFileDescriptor.parseMode(mode));
            if ("content://mms/part/1".equals(uri.toString()) && attachment != null)
                return ParcelFileDescriptor.open(attachment, ParcelFileDescriptor.MODE_READ_ONLY);
            throw new FileNotFoundException();
        }

        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            inserts++;
            throw new UnsupportedOperationException();
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
