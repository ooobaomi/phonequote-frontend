package com.local.phonequote;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuoteUploadRequest {
    public final String tableName;
    public final List<FilePart> files;
    public final boolean shareWithTeam;

    public QuoteUploadRequest(String tableName, List<FilePart> files) {
        this(tableName, files, false);
    }

    public QuoteUploadRequest(String tableName, List<FilePart> files, boolean shareWithTeam) {
        this.tableName = tableName;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        this.shareWithTeam = shareWithTeam;
    }

    public long totalSizeBytes() {
        long total = 0L;
        for (FilePart file : files) {
            if (file.sizeBytes > 0) {
                total += file.sizeBytes;
            }
        }
        return total;
    }

    public static final class FilePart {
        public final Uri contentUri;
        public final String displayName;
        public final String mimeType;
        public final long sizeBytes;

        public FilePart(Uri contentUri, String displayName, String mimeType, long sizeBytes) {
            this.contentUri = contentUri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }
    }
}
