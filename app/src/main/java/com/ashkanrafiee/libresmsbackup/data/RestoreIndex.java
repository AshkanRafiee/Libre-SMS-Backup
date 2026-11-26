package com.ashkanrafiee.libresmsbackup.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/** A restore-local index for hashed keys, used and closed on the restore thread. */
final class RestoreIndex implements Closeable {
    private static final String FILE_PREFIX = "libresmsbackup-restore-index-";
    private static boolean clearedPreviousIndexes;
    private final File file;
    private final SQLiteDatabase database;

    RestoreIndex(Context context) throws IOException {
        File cache = context.getCacheDir();
        if (cache == null) throw new IOException("restore cache is unavailable");
        deletePreviousIndexes(cache);
        file = File.createTempFile(FILE_PREFIX, ".db", cache);
        SQLiteDatabase opened = null;
        try {
            opened = openScratchDatabase(file);
            // Negative cache_size is measured in KiB instead of pages.
            opened.execSQL("PRAGMA cache_size = -2048");
            if (Build.VERSION.SDK_INT < 28) {
                try (SQLiteStatement mode = opened.compileStatement("PRAGMA journal_mode = MEMORY")) {
                    if (!"memory".equalsIgnoreCase(mode.simpleQueryForString())) {
                        throw new SQLiteException("cannot configure restore index journal");
                    }
                }
                opened.execSQL("PRAGMA synchronous = OFF");
            }
            opened.execSQL("CREATE TABLE restore_keys (scope INTEGER NOT NULL, digest TEXT NOT NULL,"
                    + " copies INTEGER NOT NULL, PRIMARY KEY (scope, digest)) WITHOUT ROWID");
            opened.execSQL("CREATE TABLE mms_candidates (digest TEXT NOT NULL, id INTEGER NOT NULL,"
                    + " PRIMARY KEY (digest, id)) WITHOUT ROWID");
        } catch (RuntimeException | Error failure) {
            try {
                closeDatabase(opened, file);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof SQLiteException) {
                throw new IOException("cannot open restore index", failure);
            }
            throw failure;
        }
        database = opened;
    }

    private static synchronized void deletePreviousIndexes(File cache) {
        if (clearedPreviousIndexes) return;
        // This app uses one process. A fresh process has no live restore indexes.
        File[] indexes = cache.listFiles((directory, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(".db"));
        if (indexes != null) for (File old : indexes) SQLiteDatabase.deleteDatabase(old);
        clearedPreviousIndexes = true;
    }

    private static SQLiteDatabase openScratchDatabase(File file) {
        int flags = SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        if (Build.VERSION.SDK_INT >= 28) {
            // Keep rollback available without writing a journal or syncing this disposable database.
            // Setting the mode at open also prevents Android from enabling compatibility WAL.
            SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(flags)
                    .setJournalMode(SQLiteDatabase.JOURNAL_MODE_MEMORY)
                    .setSynchronousMode(SQLiteDatabase.SYNC_MODE_OFF)
                    .build();
            return SQLiteDatabase.openDatabase(file, params);
        }
        return SQLiteDatabase.openDatabase(file.getPath(), null, flags);
    }

    void add(int scope, String key) {
        database.execSQL("INSERT OR REPLACE INTO restore_keys (scope, digest, copies)"
                + " SELECT ?1, ?2, 1 WHERE NOT EXISTS (SELECT 1 FROM restore_keys"
                + " WHERE scope = ?1 AND digest = ?2 AND copies > 0)", new Object[]{scope, key});
    }

    boolean contains(int scope, String key) {
        try (SQLiteStatement query = database.compileStatement("SELECT EXISTS (SELECT 1 FROM restore_keys"
                + " WHERE scope = ? AND digest = ? AND copies > 0)")) {
            query.bindLong(1, scope);
            query.bindString(2, key);
            return query.simpleQueryForLong() != 0;
        }
    }

    void count(int scope, String key) {
        database.execSQL("INSERT OR REPLACE INTO restore_keys (scope, digest, copies)"
                + " VALUES (?1, ?2, 1 + COALESCE((SELECT copies FROM restore_keys"
                + " WHERE scope = ?1 AND digest = ?2), 0))", new Object[]{scope, key});
    }

    boolean consume(int scope, String key) {
        try (SQLiteStatement update = database.compileStatement("UPDATE restore_keys SET copies = copies - 1"
                + " WHERE scope = ? AND digest = ? AND copies > 0")) {
            update.bindLong(1, scope);
            update.bindString(2, key);
            return update.executeUpdateDelete() != 0;
        }
    }

    void addMmsCandidate(String key, long id) {
        database.execSQL("INSERT OR IGNORE INTO mms_candidates (digest, id) VALUES (?, ?)",
                new Object[]{key, id});
    }

    /** Returns provider IDs for this hash; the caller closes the cursor. */
    Cursor mmsCandidates(String key) {
        return database.rawQuery("SELECT id FROM mms_candidates WHERE digest = ? ORDER BY id",
                new String[]{key});
    }

    void beginSeed() {
        database.beginTransaction();
    }

    /** Ends a seed transaction; commits only after the whole seed operation succeeds. */
    void endSeed(boolean successful) {
        try {
            if (successful) database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    @Override
    public void close() throws IOException {
        closeDatabase(database, file);
    }

    private static void closeDatabase(SQLiteDatabase database, File file) throws IOException {
        try {
            if (database != null && database.isOpen()) {
                try {
                    if (database.inTransaction()) database.endTransaction();
                } finally {
                    database.close();
                }
            }
        } finally {
            SQLiteDatabase.deleteDatabase(file);
            String path = file.getPath();
            if (file.exists() || new File(path + "-journal").exists()
                    || new File(path + "-wal").exists() || new File(path + "-shm").exists()) {
                throw new IOException("cannot delete restore index");
            }
        }
    }
}
