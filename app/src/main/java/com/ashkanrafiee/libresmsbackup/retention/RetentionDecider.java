package com.ashkanrafiee.libresmsbackup.retention;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure retention calculation shared by manual and scheduled backup runs. */
public final class RetentionDecider {
    private RetentionDecider() {}

    public static Set<String> decide(List<StoredBackup> backups, RetentionPolicy policy, LocalDateTime now) {
        Set<String> keep = new HashSet<>();
        if (policy.keepAll()) {
            for (StoredBackup backup : backups) keep.add(backup.id);
            return keep;
        }
        if (policy.mode == RetentionPolicy.Mode.PERIODIC) return periodic(backups, policy, now.toLocalDate());
        List<StoredBackup> sorted = newestFirst(backups);
        for (int i = 0; i < sorted.size() && i < policy.simpleKeep; i++) keep.add(sorted.get(i).id);
        return keep;
    }

    private static Set<String> periodic(List<StoredBackup> backups, RetentionPolicy policy, LocalDate now) {
        List<StoredBackup> sorted = newestFirst(backups);
        if (sorted.isEmpty()) return new HashSet<>();
        if (policy.dailyKeep + policy.weeklyKeep + policy.monthlyKeep == 0) {
            Set<String> safety = new HashSet<>(); safety.add(sorted.get(0).id); return safety;
        }
        Set<String> keep = new HashSet<>();
        if (policy.dailyKeep > 0) pick(sorted, keep, now, policy.dailyKeep, Frame.DAY);
        if (policy.weeklyKeep > 0) pick(sorted, keep, now, policy.weeklyKeep, Frame.WEEK);
        if (policy.monthlyKeep > 0) pick(sorted, keep, now, policy.monthlyKeep, Frame.MONTH);
        return keep;
    }

    private enum Frame { DAY, WEEK, MONTH }

    private static void pick(List<StoredBackup> sorted, Set<String> keep, LocalDate now, int count, Frame frame) {
        Set<Long> seen = new HashSet<>();
        for (StoredBackup backup : sorted) {
            long index = indexOf(backup.at.toLocalDate(), now, frame);
            if (index < 0) { keep.add(backup.id); continue; }
            if (index < count && seen.add(index)) keep.add(backup.id);
        }
    }

    private static long indexOf(LocalDate backup, LocalDate now, Frame frame) {
        switch (frame) {
            case WEEK: return ChronoUnit.WEEKS.between(weekStart(backup), weekStart(now));
            case MONTH: return ChronoUnit.MONTHS.between(YearMonth.from(backup), YearMonth.from(now));
            default: return ChronoUnit.DAYS.between(backup, now);
        }
    }

    private static LocalDate weekStart(LocalDate date) { return date.with(DayOfWeek.MONDAY); }

    private static List<StoredBackup> newestFirst(List<StoredBackup> backups) {
        List<StoredBackup> sorted = new ArrayList<>(backups);
        sorted.sort((a, b) -> {
            int byTime = b.at.compareTo(a.at);
            return byTime != 0 ? byTime : a.id.compareTo(b.id);
        });
        return sorted;
    }
}
