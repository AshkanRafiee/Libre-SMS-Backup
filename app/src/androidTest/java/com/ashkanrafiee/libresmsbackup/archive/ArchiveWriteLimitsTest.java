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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
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
            file.setLength(7 * 1024 * 1024);
        }
        assertEquals(1, create().mmsCount);
        assertTrue(provider.archive.length() > 8 * 1024 * 1024);
        try (ArchiveFormat.PreparedRestore prepared =
                     ArchiveFormat.prepareRestore(context, DESTINATION, new char[0])) {
            assertEquals(1, prepared.summary.mmsCount);
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
        } catch (IOException expected) {
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

        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                String order) {
            switch (uri.toString()) {
                case "content://sms": return sms == null ? new MatrixCursor(new String[]{"body"}) : sms;
                case "content://mms": return mms ? row(new String[]{"_id"}, 1L) : null;
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
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
