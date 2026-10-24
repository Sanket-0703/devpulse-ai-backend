package com.devpulse.backend.dto.github;

import com.devpulse.backend.entity.SyncStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Repository sync state for the Repositories page. */
public record SyncStatusResponse(
        @Schema(requiredMode = REQUIRED) boolean connected,
        @Schema(requiredMode = REQUIRED) boolean installed,
        @Schema(requiredMode = REQUIRED) SyncStatus status,
        @Schema(description = "When the last successful sync finished") Instant lastSyncedAt,
        @Schema(description = "Why the last sync failed") String error,
        @Schema(requiredMode = REQUIRED) long repositoryCount
) {

    public static SyncStatusResponse notConnected() {
        return new SyncStatusResponse(false, false, SyncStatus.NEVER, null, null, 0);
    }
}
