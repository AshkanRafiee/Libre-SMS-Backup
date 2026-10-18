package com.ashkanrafiee.libresmsbackup.retention;

import java.time.LocalDateTime;

/** A timestamped backup candidate identified by its filename base. */
public final class StoredBackup {
    public final String id;
    public final LocalDateTime at;

    public StoredBackup(String id, LocalDateTime at) {
        this.id = id;
        this.at = at;
    }
}
