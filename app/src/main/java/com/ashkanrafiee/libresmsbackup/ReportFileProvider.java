package com.ashkanrafiee.libresmsbackup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only provider for sharing one generated diagnostic file with the chosen app. */
public final class ReportFileProvider extends ContentProvider {
    private static final String[] COLUMNS = {"_display_name", "_size"};

    static Uri uriFor(File file, String authority) {
        return new Uri.Builder().scheme("content").authority(authority).appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        try { reportFile(uri); return "text/plain"; }
        catch (FileNotFoundException error) { return null; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("report is read-only");
        return ParcelFileDescriptor.open(reportFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        final File file;
        try { file = reportFile(uri); }
        catch (FileNotFoundException error) { throw new IllegalArgumentException("invalid report URI", error); }
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        String[] columns = projection == null ? COLUMNS : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if ("_display_name".equals(columns[i])) row[i] = file.getName();
            else if ("_size".equals(columns[i])) row[i] = file.length();
            else row[i] = null;
        }
        result.addRow(row);
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    private File reportFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null || uri.getPathSegments().size() != 1)
            throw new FileNotFoundException("invalid report URI");
        String name = uri.getPathSegments().get(0);
        if (!name.matches("report-[0-9a-fA-F-]+\\.txt")) throw new FileNotFoundException("invalid report name");
        File directory = new File(getContext().getCacheDir(), "reports");
        try {
            File canonicalDirectory = directory.getCanonicalFile();
            File file = new File(directory, name).getCanonicalFile();
            if (!canonicalDirectory.equals(file.getParentFile()) || !file.isFile())
                throw new FileNotFoundException("report does not exist");
            return file;
        } catch (IOException error) {
            throw new FileNotFoundException("cannot open report");
        }
    }
}
