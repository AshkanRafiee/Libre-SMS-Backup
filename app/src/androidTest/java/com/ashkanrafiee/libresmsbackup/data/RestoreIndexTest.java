package com.ashkanrafiee.libresmsbackup.data;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.util.Base64;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

public class RestoreIndexTest extends TestCase {
    private File cache;
    private Context context;

    @Override
    protected void setUp() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cache = Files.createTempDirectory(target.getCacheDir().toPath(), "restore-index-test-").toFile();
        context = new ContextWrapper(target) {
            @Override
            public File getCacheDir() {
                return cache;
            }
        };
    }

    @Override
    protected void tearDown() throws Exception {
        File[] remaining = cache.listFiles();
        if (remaining != null) {
            for (File file : remaining) Files.delete(file.toPath());
        }
        Files.delete(cache.toPath());
    }

    public void testMembershipCountsAndMmsCandidatesStayIsolated() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String key = hash(digest, 0);
        String other = hash(digest, 1);
        try (RestoreIndex index = new RestoreIndex(context)) {
            assertFalse(index.contains(1, key));
            assertFalse(index.consume(2, key));
            index.add(1, key);
            index.add(1, key);
            assertTrue(index.contains(1, key));
            assertFalse(index.contains(1, other));
            assertFalse(index.contains(2, key));
            assertTrue(index.consume(1, key));
            assertFalse(index.consume(1, key));
            index.add(1, key);

            index.beginSeed();
            try {
                index.count(2, key);
                index.addMmsCandidate(key, 9);
            } finally {
                index.endSeed(false);
            }
            assertFalse(index.consume(2, key));
            try (Cursor candidates = index.mmsCandidates(key)) {
                assertFalse(candidates.moveToFirst());
            }

            index.count(2, key);
            index.count(2, key);
            assertTrue(index.consume(2, key));
            assertTrue(index.consume(2, key));
            assertFalse(index.consume(2, key));
            assertTrue(index.contains(1, key));

            long largeId = 1L << 32;
            index.addMmsCandidate(key, largeId);
            index.addMmsCandidate(key, 12);
            index.addMmsCandidate(key, 12);
            index.addMmsCandidate(other, 8);
            try (Cursor candidates = index.mmsCandidates(key)) {
                assertEquals(1, candidates.getColumnCount());
                assertTrue(candidates.moveToNext());
                assertEquals(12L, candidates.getLong(0));
                assertTrue(candidates.moveToNext());
                assertEquals(largeId, candidates.getLong(0));
                assertFalse(candidates.moveToNext());
            }
        }
        assertEquals(0, cache.list().length);
    }

    public void testCleanupAfterFailureLeavesAnotherOpenIndexUsable() throws Exception {
        String key = hash(MessageDigest.getInstance("SHA-256"), 0);
        IOException failure = new IOException("seed interrupted");
        try (RestoreIndex active = new RestoreIndex(context)) {
            active.add(1, key);
            assertEquals(1, cache.list().length);
            try (RestoreIndex interrupted = new RestoreIndex(context)) {
                assertEquals(2, cache.list().length);
                assertFalse(interrupted.contains(1, key));
                interrupted.beginSeed();
                interrupted.add(2, key);
                throw failure;
            } catch (IOException expected) {
                assertSame(failure, expected);
                assertEquals(0, expected.getSuppressed().length);
            }
            assertEquals(1, cache.list().length);
            assertTrue(active.contains(1, key));
        }
        assertEquals(0, cache.list().length);
    }

    @LargeTest
    public void testBatchedSeedOfOneHundredThousandHashedKeys() throws Exception {
        int size = 100_000;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (RestoreIndex index = new RestoreIndex(context)) {
            boolean seeded = false;
            index.beginSeed();
            try {
                for (int record = 0; record < size; record++) {
                    index.add(1, hash(digest, record));
                }
                seeded = true;
            } finally {
                index.endSeed(seeded);
            }
            for (int record = 0; record < size; record++) {
                assertTrue(index.contains(1, hash(digest, record)));
            }
            assertFalse(index.contains(1, hash(digest, size)));
            assertFalse(index.contains(2, hash(digest, size - 1)));
        }
        assertEquals(0, cache.list().length);
    }

    private static String hash(MessageDigest digest, int value) {
        byte[] source = Integer.toString(value).getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(digest.digest(source), Base64.NO_WRAP);
    }
}
