package com.local.phonequote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuoteUploadJob {
    public enum Status {
        QUEUED,
        UPLOADING,
        OCR,
        NORMALIZING,
        REVIEW_REQUIRED,
        READY,
        DOWNLOADING,
        IMPORTED,
        FAILED,
        CANCELLED
    }

    public final String id;
    public final String tableId;
    public final Status status;
    public final int progress;
    public final String message;
    public final int itemCount;
    public final String brandSummary;
    public final List<String> previewRows;
    public final int revision;
    public final String sha256;

    public QuoteUploadJob(
            String id,
            String tableId,
            Status status,
            int progress,
            String message,
            int itemCount,
            String brandSummary,
            List<String> previewRows
    ) {
        this(id, tableId, status, progress, message, itemCount, brandSummary,
                previewRows, 0, "");
    }

    public QuoteUploadJob(
            String id,
            String tableId,
            Status status,
            int progress,
            String message,
            int itemCount,
            String brandSummary,
            List<String> previewRows,
            int revision,
            String sha256
    ) {
        this.id = id;
        this.tableId = tableId;
        this.status = status;
        this.progress = progress;
        this.message = message;
        this.itemCount = itemCount;
        this.brandSummary = brandSummary;
        this.previewRows = Collections.unmodifiableList(new ArrayList<>(previewRows));
        this.revision = revision;
        this.sha256 = sha256 == null ? "" : sha256;
    }
}
