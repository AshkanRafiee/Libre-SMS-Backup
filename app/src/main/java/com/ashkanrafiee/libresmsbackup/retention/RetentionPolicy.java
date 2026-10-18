package com.ashkanrafiee.libresmsbackup.retention;

/** Simple or age-tiered retention settings for timestamped SMS backup files. */
public final class RetentionPolicy {
    public enum Mode { SIMPLE, PERIODIC }

    public static final int KEEP_ALL = 9999;
    public static final int DEFAULT_KEEP = 5;
    public static final int DEFAULT_DAILY = 7;
    public static final int DEFAULT_WEEKLY = 4;
    public static final int DEFAULT_MONTHLY = 3;

    public final Mode mode;
    public final int simpleKeep;
    public final int dailyKeep;
    public final int weeklyKeep;
    public final int monthlyKeep;

    public RetentionPolicy(Mode mode, int simpleKeep, int dailyKeep, int weeklyKeep, int monthlyKeep) {
        this.mode = mode;
        this.simpleKeep = Math.max(1, simpleKeep);
        this.dailyKeep = Math.max(0, dailyKeep);
        this.weeklyKeep = Math.max(0, weeklyKeep);
        this.monthlyKeep = Math.max(0, monthlyKeep);
    }

    public boolean keepAll() {
        return mode == Mode.SIMPLE ? simpleKeep > 100 : dailyKeep > 100;
    }
}
