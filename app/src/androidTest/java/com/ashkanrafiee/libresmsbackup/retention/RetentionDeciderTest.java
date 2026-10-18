package com.ashkanrafiee.libresmsbackup.retention;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;

import junit.framework.TestCase;

/** Keeps retention behavior deterministic as the storage UI evolves. */
public class RetentionDeciderTest extends TestCase {
    public void testSimpleKeepsNewestSets() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
        Set<String> keep = RetentionDecider.decide(Arrays.asList(
                new StoredBackup("new", now),
                new StoredBackup("old", now.minusDays(1)),
                new StoredBackup("older", now.minusDays(2))),
                new RetentionPolicy(RetentionPolicy.Mode.SIMPLE, 2, 7, 4, 3), now);
        assertTrue(keep.contains("new"));
        assertTrue(keep.contains("old"));
        assertFalse(keep.contains("older"));
    }

    public void testPeriodicKeepsRecentDailyFrames() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
        Set<String> keep = RetentionDecider.decide(Arrays.asList(
                new StoredBackup("today", now),
                new StoredBackup("yesterday", now.minusDays(1)),
                new StoredBackup("older", now.minusDays(3))),
                new RetentionPolicy(RetentionPolicy.Mode.PERIODIC, 5, 2, 0, 0), now);
        assertEquals(2, keep.size());
        assertTrue(keep.contains("today"));
        assertTrue(keep.contains("yesterday"));
    }
}
