package com.local.phonequote;

import java.util.List;

public interface QuoteTableApi {
    void listTables(ApiCallback<List<QuoteTableInfo>> callback);

    void uploadAndConvert(
            QuoteUploadRequest request,
            UploadProgressCallback progressCallback,
            ApiCallback<QuoteUploadJob> callback
    );

    void getUploadJob(String jobId, ApiCallback<QuoteUploadJob> callback);

    void downloadAndImport(QuoteUploadJob job, ApiCallback<QuoteUploadJob> callback);

    void activateTable(String tableId, ApiCallback<QuoteTableInfo> callback);

    void selectTable(String tableId, ApiCallback<QuoteTableInfo> callback);

    void deselectTable(String tableId, ApiCallback<QuoteTableInfo> callback);

    void updateSharing(String tableId, boolean sharedWithTeam, ApiCallback<QuoteTableInfo> callback);

    void deleteLocalTable(QuoteTableInfo table, ApiCallback<Boolean> callback);

    void deleteCloudTable(QuoteTableInfo table, ApiCallback<Boolean> callback);
}
