package com.local.phonequote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuoteTableInfo {
    public enum Status {
        READY,
        PROCESSING,
        FAILED,
        EXPIRED
    }

    public enum LocalState {
        NOT_DOWNLOADED,
        DOWNLOADING,
        IMPORTED,
        EXPIRED_LOCAL_ONLY,
        LOCAL_ONLY_CLOUD_DELETED,
        LOCAL_ONLY_MANUAL
    }

    public final String id;
    public final String name;
    public final String sourceType;
    public final String updatedAt;
    public final int itemCount;
    public final String brandSummary;
    public final boolean active;
    public final Status status;
    public final int revision;
    public final int serverVersion;
    public final LocalState localState;
    public final long downloadedAtMillis;
    public final List<String> brands;
    public final int selectionOrder;
    public final boolean cloudAvailable;
    public final long cloudExpiresAtMillis;
    public final String accessScope;
    public final boolean sharedWithTeam;
    public final String uploaderName;
    public final boolean canManageSharing;

    public QuoteTableInfo(
            String id,
            String name,
            String sourceType,
            String updatedAt,
            int itemCount,
            String brandSummary,
            boolean active,
            Status status
    ) {
        this(id, name, sourceType, updatedAt, itemCount, brandSummary, active, status,
                1, 1, LocalState.IMPORTED, 0L,
                Collections.emptyList(), active ? 1 : 0, true, 0L);
    }

    public QuoteTableInfo(
            String id,
            String name,
            String sourceType,
            String updatedAt,
            int itemCount,
            String brandSummary,
            boolean active,
            Status status,
            int revision,
            int serverVersion,
            LocalState localState,
            long downloadedAtMillis
    ) {
        this(id, name, sourceType, updatedAt, itemCount, brandSummary, active, status,
                revision, serverVersion, localState, downloadedAtMillis,
                Collections.emptyList(), active ? 1 : 0, true, 0L,
                "OWN", false, "", false);
    }

    public QuoteTableInfo(
            String id,
            String name,
            String sourceType,
            String updatedAt,
            int itemCount,
            String brandSummary,
            boolean active,
            Status status,
            int revision,
            int serverVersion,
            LocalState localState,
            long downloadedAtMillis,
            List<String> brands,
            int selectionOrder,
            boolean cloudAvailable,
            long cloudExpiresAtMillis
    ) {
        this(id, name, sourceType, updatedAt, itemCount, brandSummary, active, status,
                revision, serverVersion, localState, downloadedAtMillis, brands,
                selectionOrder, cloudAvailable, cloudExpiresAtMillis,
                "OWN", false, "", false);
    }

    public QuoteTableInfo(
            String id,
            String name,
            String sourceType,
            String updatedAt,
            int itemCount,
            String brandSummary,
            boolean active,
            Status status,
            int revision,
            int serverVersion,
            LocalState localState,
            long downloadedAtMillis,
            List<String> brands,
            int selectionOrder,
            boolean cloudAvailable,
            long cloudExpiresAtMillis,
            String accessScope,
            boolean sharedWithTeam,
            String uploaderName,
            boolean canManageSharing
    ) {
        this.id = id;
        this.name = name;
        this.sourceType = sourceType;
        this.updatedAt = updatedAt;
        this.itemCount = itemCount;
        this.brandSummary = brandSummary;
        this.active = active;
        this.status = status;
        this.revision = revision;
        this.serverVersion = serverVersion;
        this.localState = localState;
        this.downloadedAtMillis = downloadedAtMillis;
        this.brands = Collections.unmodifiableList(new ArrayList<>(brands));
        this.selectionOrder = selectionOrder;
        this.cloudAvailable = cloudAvailable;
        this.cloudExpiresAtMillis = cloudExpiresAtMillis;
        this.accessScope = accessScope;
        this.sharedWithTeam = sharedWithTeam;
        this.uploaderName = uploaderName;
        this.canManageSharing = canManageSharing;
    }
}
