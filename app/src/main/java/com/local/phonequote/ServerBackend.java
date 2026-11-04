package com.local.phonequote;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

public final class ServerBackend implements AuthApi, QuoteTableApi, TeamApi {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PREFS = "server_session";
    private static final String KEY_ACCOUNT_ID = "account_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_ACCESS_EXPIRES = "access_expires";
    private static final String KEY_ACCOUNT_EXPIRES = "account_expires";
    private static final String KEY_MUST_CHANGE = "must_change";
    private static final String KEY_PROFILE_COMPLETED = "profile_completed";
    private static final String KEY_ACCOUNT_TYPE = "account_type";
    private static final String KEY_TEAM_ID = "team_id";
    private static final String KEY_TEAM_NAME = "team_name";
    private static final String KEY_TEAM_ROLE = "team_role";
    private static final String KEY_TEAM_STATUS = "team_status";
    private static final String KEY_TEAM_VERSION = "team_version";
    private static final String KEY_QUOTE_CATALOG_VERSION = "quote_catalog_version";
    private static final String KEY_CAN_UPLOAD = "can_upload_quotes";
    private static final String KEY_PENDING_DESELECT = "pending_deselect";
    private static final String KEY_PENDING_DELETE = "pending_delete";
    private static final String KEY_SELECTION_MIGRATED_V2 = "selection_migrated_v2";

    private final Context context;
    private final String baseUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build();
    private final SharedPreferences sessionPreferences;

    public ServerBackend(Context context, String baseUrl) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        sessionPreferences = createPreferences(this.context);
    }

    private SharedPreferences createPreferences(Context target) {
        try {
            MasterKey masterKey = new MasterKey.Builder(target)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    target,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception ignored) {
            return target.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    @Override
    public void sendRegisterCode(String phone, ApiCallback<Integer> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("phone", phone);
                JSONObject result = executeJson("auth/sms/send", "POST", body, false);
                success(callback, result.optInt("retry_after", 60));
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void register(String name, String phone, String password, String code, ApiCallback<AccountSession> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("name", name)
                        .put("phone", phone)
                        .put("password", password)
                        .put("code", code);
                AccountSession session = parseSession(executeJson("auth/register", "POST", body, false), true);
                saveSession(session);
                success(callback, session);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void login(String account, String password, ApiCallback<AccountSession> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("phone", account).put("password", password);
                AccountSession session = parseSession(executeJson("auth/login", "POST", body, false), true);
                saveSession(session);
                success(callback, session);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void getCurrentSession(ApiCallback<AccountSession> callback) {
        if (sessionPreferences.getString(KEY_REFRESH_TOKEN, "").isEmpty()) {
            error(callback, "登录状态已失效");
            return;
        }
        executor.execute(() -> {
            try {
                int previousTeamVersion = sessionPreferences.getInt(KEY_TEAM_VERSION, 0);
                int previousCatalogVersion = sessionPreferences.getInt(KEY_QUOTE_CATALOG_VERSION, 1);
                String previousTeamId = sessionPreferences.getString(KEY_TEAM_ID, "");
                AccountSession session = parseSession(executeJson("auth/me", "GET", null, true), false);
                saveSession(session);
                if (previousTeamVersion != session.teamVersion
                        || previousCatalogVersion != session.quoteCatalogVersion
                        || !previousTeamId.equals(session.teamId)) {
                    synchronizeTeamAccess();
                }
                success(callback, session);
            } catch (Exception exception) {
                if (exception instanceof ApiException
                        && (((ApiException) exception).statusCode == 401
                        || ((ApiException) exception).statusCode == 403)) {
                    clearSession();
                }
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void logout(ApiCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put(
                        "refresh_token", sessionPreferences.getString(KEY_REFRESH_TOKEN, "")
                );
                executeJson("auth/logout", "POST", body, true);
            } catch (Exception ignored) {
                // 本地会话仍然需要立即清除。
            }
            clearSession();
            success(callback, true);
        });
    }

    @Override
    public void changePassword(String currentPassword, String newPassword, ApiCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("current_password", currentPassword)
                        .put("new_password", newPassword);
                executeJson("auth/change-password", "POST", body, true);
                clearSession();
                success(callback, true);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void updateProfile(String name, ApiCallback<AccountSession> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("name", name);
                AccountSession session = parseSession(
                        executeJson("auth/profile", "PATCH", body, true), false
                );
                saveSession(session);
                success(callback, session);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void getSupport(ApiCallback<SupportInfo> callback) {
        executor.execute(() -> {
            try {
                JSONObject result = executeJson("system/support", "GET", null, false);
                success(callback, new SupportInfo(
                        result.optString("phone"), result.optString("wechat"), result.optString("message")
                ));
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void listTables(ApiCallback<List<QuoteTableInfo>> callback) {
        executor.execute(() -> {
            try {
                flushPendingTableOperations();
                JSONArray result = executeArray("quote-tables", true);
                result = migrateLocalSelections(result);
                syncSelectedTables(result);
                List<QuoteTableInfo> tables = new ArrayList<>();
                java.util.Set<String> serverIds = new java.util.HashSet<>();
                for (int i = 0; i < result.length(); i++) {
                    QuoteTableInfo table = parseTable(result.getJSONObject(i));
                    tables.add(table);
                    serverIds.add(table.id);
                }
                for (QuoteTableInfo local : QuoteRepository.localImportedTableInfos(context)) {
                    if (!serverIds.contains(local.id)) {
                        tables.add(local);
                    }
                }
                QuoteTableInfo localOnly = QuoteRepository.localOnlyTableInfo(context);
                if (localOnly != null) {
                    tables.add(localOnly);
                }
                success(callback, tables);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void uploadAndConvert(
            QuoteUploadRequest request,
            UploadProgressCallback progressCallback,
            ApiCallback<QuoteUploadJob> callback
    ) {
        executor.execute(() -> {
            try {
                long total = Math.max(1L, request.totalSizeBytes());
                ProgressTracker tracker = new ProgressTracker(total, progressCallback);
                MultipartBody.Builder multipart = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("name", request.tableName)
                        .addFormDataPart("share_with_team", String.valueOf(request.shareWithTeam));
                for (QuoteUploadRequest.FilePart file : request.files) {
                    multipart.addFormDataPart(
                            "files",
                            file.displayName,
                            new UriRequestBody(context, file, tracker)
                    );
                }
                Request httpRequest = authorized(new Request.Builder()
                        .url(url("quote-tables/uploads"))
                        .post(multipart.build()))
                        .build();
                try (Response response = executeAuthorized(httpRequest)) {
                    JSONObject result = responseObject(response);
                    success(callback, parseJob(result));
                }
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void getUploadJob(String jobId, ApiCallback<QuoteUploadJob> callback) {
        executor.execute(() -> {
            try {
                success(callback, parseJob(executeJson("quote-tables/jobs/" + jobId, "GET", null, true)));
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void downloadAndImport(QuoteUploadJob job, ApiCallback<QuoteUploadJob> callback) {
        executor.execute(() -> {
            try {
                int count = downloadAndImportSync(job.tableId, job.revision, job.sha256, "云端报价表");
                QuoteUploadJob imported = new QuoteUploadJob(
                        job.id, job.tableId, QuoteUploadJob.Status.IMPORTED, 100,
                        "报价表已下载并导入", count, job.brandSummary,
                        job.previewRows, job.revision, job.sha256
                );
                success(callback, imported);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void activateTable(String tableId, ApiCallback<QuoteTableInfo> callback) {
        executor.execute(() -> {
            try {
                QuoteTableInfo selected = null;
                JSONArray tables = executeArray("quote-tables", true);
                for (int i = 0; i < tables.length(); i++) {
                    QuoteTableInfo value = parseTable(tables.getJSONObject(i));
                    if (value.id.equals(tableId)) {
                        selected = value;
                        break;
                    }
                }
                if (selected == null || selected.revision <= 0) {
                    throw new ApiException(404, "报价表不存在或尚未发布");
                }
                if (!QuoteRepository.isTableImported(context, tableId, selected.revision)) {
                    downloadAndImportSync(tableId, selected.revision, "", selected.name);
                }
                executeJson("quote-tables/" + tableId + "/activate", "POST", new JSONObject(), true);
                QuoteRepository.activateTable(context, tableId, selected.revision);
                if (CaptureService.isRunning()) {
                    context.startService(new Intent(context, CaptureService.class)
                            .setAction(CaptureService.ACTION_RELOAD_DATA));
                }
                QuoteTableInfo result = new QuoteTableInfo(
                        selected.id, selected.name, selected.sourceType, selected.updatedAt,
                        selected.itemCount, selected.brandSummary, true, selected.status,
                        selected.revision, selected.serverVersion, QuoteTableInfo.LocalState.IMPORTED,
                        QuoteRepository.downloadedAt(context, selected.id, selected.revision)
                );
                success(callback, result);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void selectTable(String tableId, ApiCallback<QuoteTableInfo> callback) {
        executor.execute(() -> {
            try {
                QuoteTableInfo selected = findServerTable(tableId);
                if (selected == null && QuoteRepository.isCloudDeletedLocal(context, tableId)) {
                    selected = findLocalTable(tableId);
                    if (selected == null) {
                        throw new ApiException(404, "本机报价表不存在");
                    }
                    QuoteRepository.selectTable(
                            context, tableId, selected.revision,
                            Math.max(1, selected.selectionOrder)
                    );
                    reloadCaptureData();
                    success(callback, selected);
                    return;
                }
                if (selected == null || selected.revision <= 0) {
                    throw new ApiException(404, "报价表不存在或尚未发布");
                }
                if (!QuoteRepository.isTableImported(context, tableId, selected.revision)) {
                    if (!selected.cloudAvailable) {
                        throw new ApiException(410, "云端报价已过期，本机也没有可用副本");
                    }
                    downloadAndImportSync(tableId, selected.revision, "", selected.name);
                }
                JSONObject response = executeJson("quote-tables/" + tableId + "/select", "POST", new JSONObject(), true);
                int order = response.optInt("selection_order", selected.selectionOrder);
                QuoteRepository.selectTable(context, tableId, selected.revision, order);
                reloadCaptureData();
                success(callback, parseTable(response));
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void deselectTable(String tableId, ApiCallback<QuoteTableInfo> callback) {
        executor.execute(() -> {
            try {
                if (QuoteRepository.isCloudDeletedLocal(context, tableId)) {
                    QuoteTableInfo local = findLocalTable(tableId);
                    QuoteRepository.deselectTable(context, tableId);
                    reloadCaptureData();
                    success(callback, local);
                    return;
                }
                JSONObject response = executeJson("quote-tables/" + tableId + "/select", "DELETE", null, true);
                QuoteRepository.deselectTable(context, tableId);
                reloadCaptureData();
                success(callback, parseTable(response));
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void updateSharing(String tableId, boolean sharedWithTeam, ApiCallback<QuoteTableInfo> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("shared_with_team", sharedWithTeam);
                JSONObject response = executeJson(
                        "quote-tables/" + tableId + "/sharing", "PATCH", body, true
                );
                synchronizeTeamAccess();
                success(callback, parseTable(response));
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void getMyTeam(ApiCallback<TeamInfo> callback) {
        executeTeamRequest("teams/me", "GET", null, callback);
    }

    @Override
    public void createTeam(String name, ApiCallback<TeamInfo> callback) {
        try {
            executeTeamRequest("teams", "POST", new JSONObject().put("name", name), callback);
        } catch (JSONException exception) {
            error(callback, message(exception));
        }
    }

    @Override
    public void updateTeam(String name, Boolean allowMemberUploads, ApiCallback<TeamInfo> callback) {
        try {
            JSONObject body = new JSONObject();
            if (name != null) body.put("name", name);
            if (allowMemberUploads != null) body.put("allow_member_uploads", allowMemberUploads);
            executeTeamRequest("teams/me", "PATCH", body, callback);
        } catch (JSONException exception) {
            error(callback, message(exception));
        }
    }

    @Override
    public void addTeamMember(String phone, ApiCallback<TeamInfo> callback) {
        try {
            executeTeamRequest(
                    "teams/me/members", "POST", new JSONObject().put("phone", phone), callback
            );
        } catch (JSONException exception) {
            error(callback, message(exception));
        }
    }

    @Override
    public void removeTeamMember(String userId, ApiCallback<TeamInfo> callback) {
        executeTeamRequest("teams/me/members/" + userId, "DELETE", null, callback);
    }

    @Override
    public void deleteLocalTable(QuoteTableInfo table, ApiCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                if (QuoteRepository.LOCAL_ONLY_TABLE_ID.equals(table.id)) {
                    QuoteRepository.deleteLocalOnlyTable(context);
                    reloadCaptureData();
                    success(callback, true);
                    return;
                }
                QuoteRepository.deleteTable(context, table.id);
                if (table.localState != QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED) {
                    try {
                        executeJson("quote-tables/" + table.id + "/select", "DELETE", null, true);
                    } catch (Exception exception) {
                        if (!(exception instanceof ApiException)
                                || ((ApiException) exception).statusCode != 404) {
                            addPending(KEY_PENDING_DESELECT, table.id);
                        }
                    }
                }
                reloadCaptureData();
                success(callback, true);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    @Override
    public void deleteCloudTable(QuoteTableInfo table, ApiCallback<Boolean> callback) {
        executor.execute(() -> {
            try {
                executeJson("quote-tables/" + table.id + "/cloud", "DELETE", null, true);
                if (QuoteRepository.isTableImported(context, table.id, table.revision)) {
                    QuoteRepository.markCloudDeleted(context, table.id);
                }
                reloadCaptureData();
                success(callback, true);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    private QuoteTableInfo findServerTable(String tableId) throws IOException, JSONException, ApiException {
        JSONArray tables = executeArray("quote-tables", true);
        for (int i = 0; i < tables.length(); i++) {
            QuoteTableInfo value = parseTable(tables.getJSONObject(i));
            if (value.id.equals(tableId)) {
                return value;
            }
        }
        return null;
    }

    private QuoteTableInfo findLocalTable(String tableId) {
        for (QuoteTableInfo table : QuoteRepository.localImportedTableInfos(context)) {
            if (table.id.equals(tableId)) {
                return table;
            }
        }
        return null;
    }

    private void syncSelectedTables(JSONArray tables) throws IOException, JSONException, ApiException {
        List<JSONObject> selections = new ArrayList<>();
        java.util.Set<String> serverIds = new java.util.HashSet<>();
        for (int i = 0; i < tables.length(); i++) {
            JSONObject value = tables.getJSONObject(i);
            serverIds.add(value.getString("id"));
            if (!value.optBoolean("active")) {
                continue;
            }
            String tableId = value.getString("id");
            int revision = value.optInt("revision", 0);
            if (!QuoteRepository.isTableImported(context, tableId, revision)) {
                if (!value.optBoolean("cloud_available", true)) {
                    continue;
                }
                downloadAndImportSync(
                        tableId,
                        revision,
                        value.optString("sha256"),
                        value.optString("name", "云端报价表")
                );
            }
            selections.add(new JSONObject()
                    .put("tableId", tableId)
                    .put("revision", revision)
                    .put("selectionOrder", value.optInt("selection_order", i + 1)));
        }
        for (JSONObject local : QuoteRepository.selectedTables(context)) {
            String tableId = local.optString("tableId");
            if (!serverIds.contains(tableId) && QuoteRepository.isCloudDeletedLocal(context, tableId)) {
                selections.add(local);
            }
        }
        QuoteRepository.replaceSelections(context, selections);
    }

    private JSONArray migrateLocalSelections(JSONArray serverTables)
            throws IOException, JSONException, ApiException {
        if (sessionPreferences.getBoolean(KEY_SELECTION_MIGRATED_V2, false)) {
            return serverTables;
        }
        java.util.Set<String> serverIds = new java.util.HashSet<>();
        for (int i = 0; i < serverTables.length(); i++) {
            serverIds.add(serverTables.getJSONObject(i).getString("id"));
        }
        for (JSONObject local : QuoteRepository.selectedTables(context)) {
            String tableId = local.optString("tableId");
            if (serverIds.contains(tableId)) {
                executeJson("quote-tables/" + tableId + "/select", "POST", new JSONObject(), true);
            }
        }
        sessionPreferences.edit().putBoolean(KEY_SELECTION_MIGRATED_V2, true).apply();
        return executeArray("quote-tables", true);
    }

    private void reloadCaptureData() {
        if (CaptureService.isRunning()) {
            context.startService(new Intent(context, CaptureService.class)
                    .setAction(CaptureService.ACTION_RELOAD_DATA));
        }
    }

    private void synchronizeTeamAccess() throws IOException, JSONException, ApiException {
        JSONArray tables = executeArray("quote-tables", true);
        syncSelectedTables(tables);
        java.util.Set<String> accessible = new java.util.HashSet<>();
        for (int i = 0; i < tables.length(); i++) {
            accessible.add(tables.getJSONObject(i).getString("id"));
        }
        QuoteRepository.retainAccessibleTables(context, accessible);
        reloadCaptureData();
    }

    private void addPending(String key, String tableId) {
        java.util.Set<String> values = new java.util.HashSet<>(
                sessionPreferences.getStringSet(key, Collections.emptySet())
        );
        values.add(tableId);
        sessionPreferences.edit().putStringSet(key, values).apply();
    }

    private void flushPendingTableOperations() {
        flushPending(KEY_PENDING_DESELECT, true);
        flushPending(KEY_PENDING_DELETE, false);
    }

    private void flushPending(String key, boolean deselect) {
        java.util.Set<String> pending = new java.util.HashSet<>(
                sessionPreferences.getStringSet(key, Collections.emptySet())
        );
        java.util.Set<String> remaining = new java.util.HashSet<>();
        for (String tableId : pending) {
            try {
                executeJson(
                        "quote-tables/" + tableId + (deselect ? "/select" : ""),
                        "DELETE",
                        null,
                        true
                );
            } catch (Exception exception) {
                remaining.add(tableId);
            }
        }
        sessionPreferences.edit().putStringSet(key, remaining).apply();
    }

    private int downloadAndImportSync(
            String tableId,
            int revision,
            String expectedSha256,
            String name
    ) throws IOException, JSONException, ApiException {
        Request request = authorized(new Request.Builder()
                .url(url("quote-tables/" + tableId + "/download?revision=" + revision))
                .get()).build();
        try (Response response = executeAuthorized(request)) {
            if (!response.isSuccessful()) {
                throw apiError(response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("服务器返回了空报价表");
            }
            String headerSha = response.header("X-Content-SHA256", "");
            String digest = expectedSha256 == null || expectedSha256.isEmpty() ? headerSha : expectedSha256;
            try (InputStream input = body.byteStream()) {
                return QuoteRepository.importTable(context, tableId, revision, name, input, digest);
            }
        }
    }

    private AccountSession parseSession(JSONObject value, boolean includeRefresh) throws JSONException {
        String refresh = includeRefresh
                ? value.optString("refresh_token")
                : sessionPreferences.getString(KEY_REFRESH_TOKEN, "");
        return new AccountSession(
                value.getString("account_id"),
                value.optString("name", value.optString("display_name")),
                value.getString("display_name"),
                value.getString("phone"),
                value.getString("access_token"),
                refresh,
                value.optInt("access_expires_in", 900),
                parseMillis(value.getString("expires_at")),
                value.optBoolean("must_change_password", false),
                value.optBoolean("profile_completed", true),
                value.optString("account_type", "PERSONAL"),
                value.optString("team_id"),
                value.optString("team_name"),
                value.optString("team_role"),
                value.optString("team_status"),
                value.optInt("team_version", 0),
                value.optInt("quote_catalog_version", 1),
                value.optBoolean("can_upload_quotes", true)
        );
    }

    private TeamInfo parseTeam(JSONObject response) throws JSONException {
        String accountType = response.optString("account_type", "PERSONAL");
        boolean canCreate = response.optBoolean("can_create_team", false);
        JSONObject value = response.optJSONObject("team");
        if (value == null) {
            return new TeamInfo(accountType, canCreate, "", "", "", 0, 0,
                    true, 0, "", "", 0, Collections.emptyList());
        }
        List<TeamInfo.Member> members = new ArrayList<>();
        JSONArray rows = value.optJSONArray("members");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                members.add(new TeamInfo.Member(
                        row.getString("user_id"),
                        row.optString("name"),
                        row.optString("phone"),
                        row.optString("role"),
                        row.optString("status"),
                        parseOptionalMillis(row.optString("joined_at"))
                ));
            }
        }
        return new TeamInfo(
                accountType,
                canCreate,
                value.getString("id"),
                value.optString("name"),
                value.optString("status"),
                value.optInt("member_limit"),
                value.optInt("member_count", members.size()),
                value.optBoolean("allow_member_uploads", true),
                value.optInt("version"),
                value.optString("owner_id"),
                value.optString("owner_name"),
                value.optInt("shared_table_count"),
                members
        );
    }

    private void executeTeamRequest(
            String path,
            String method,
            JSONObject body,
            ApiCallback<TeamInfo> callback
    ) {
        executor.execute(() -> {
            try {
                TeamInfo team = parseTeam(executeJson(path, method, body, true));
                success(callback, team);
            } catch (Exception exception) {
                error(callback, message(exception));
            }
        });
    }

    private QuoteTableInfo parseTable(JSONObject value) throws JSONException {
        int revision = value.optInt("revision", 0);
        boolean imported = QuoteRepository.isTableImported(context, value.getString("id"), revision);
        boolean active = QuoteRepository.isTableActive(context, value.getString("id"), revision);
        String statusValue = value.optString("status", "PROCESSING");
        QuoteTableInfo.Status status;
        if ("READY".equals(statusValue)) {
            status = QuoteTableInfo.Status.READY;
        } else if ("EXPIRED".equals(statusValue)) {
            status = QuoteTableInfo.Status.EXPIRED;
        } else if ("FAILED".equals(statusValue)) {
            status = QuoteTableInfo.Status.FAILED;
        } else {
            status = QuoteTableInfo.Status.PROCESSING;
        }
        List<String> brands = new ArrayList<>();
        JSONArray brandValues = value.optJSONArray("brands");
        if (brandValues != null) {
            for (int i = 0; i < brandValues.length(); i++) {
                brands.add(brandValues.getString(i));
            }
        }
        boolean cloudAvailable = value.optBoolean("cloud_available", true);
        return new QuoteTableInfo(
                value.getString("id"),
                value.getString("name"),
                value.optString("source_type", "服务器转换"),
                shortDate(value.optString("updated_at")),
                value.optInt("item_count"),
                value.optString("brand_summary"),
                active,
                status,
                revision,
                value.optInt("server_version", revision),
                imported
                        ? (cloudAvailable ? QuoteTableInfo.LocalState.IMPORTED : QuoteTableInfo.LocalState.EXPIRED_LOCAL_ONLY)
                        : QuoteTableInfo.LocalState.NOT_DOWNLOADED,
                imported ? QuoteRepository.downloadedAt(context, value.getString("id"), revision) : 0L,
                brands,
                QuoteRepository.selectionOrder(context, value.getString("id"), revision),
                cloudAvailable,
                parseOptionalMillis(value.optString("cloud_expires_at")),
                value.optString("access_scope", "OWN"),
                value.optBoolean("shared_with_team", false),
                value.optString("uploader_name"),
                value.optBoolean("can_manage_sharing", false)
        );
    }

    private QuoteUploadJob parseJob(JSONObject value) throws JSONException {
        QuoteUploadJob.Status status;
        try {
            status = QuoteUploadJob.Status.valueOf(value.getString("status"));
        } catch (IllegalArgumentException ignored) {
            status = QuoteUploadJob.Status.FAILED;
        }
        List<String> preview = new ArrayList<>();
        JSONArray rows = value.optJSONArray("preview_rows");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                preview.add(rows.getString(i));
            }
        }
        return new QuoteUploadJob(
                value.getString("id"), value.getString("table_id"), status,
                value.optInt("progress"), value.optString("message"),
                value.optInt("item_count"), value.optString("brand_summary"),
                preview, value.optInt("revision"), value.optString("sha256")
        );
    }

    private JSONObject executeJson(String path, String method, JSONObject body, boolean auth)
            throws IOException, JSONException, ApiException {
        Request.Builder builder = new Request.Builder().url(url(path));
        if ("GET".equals(method)) {
            builder.get();
        } else {
            builder.method(method, RequestBody.create(body == null ? "{}" : body.toString(), JSON));
        }
        Request request = auth ? authorized(builder).build() : builder.build();
        try (Response response = auth ? executeAuthorized(request) : client.newCall(request).execute()) {
            return responseObject(response);
        }
    }

    private JSONArray executeArray(String path, boolean auth)
            throws IOException, JSONException, ApiException {
        Request.Builder builder = new Request.Builder().url(url(path)).get();
        Request request = auth ? authorized(builder).build() : builder.build();
        try (Response response = auth ? executeAuthorized(request) : client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw apiError(response);
            }
            ResponseBody body = response.body();
            return new JSONArray(body == null ? "[]" : body.string());
        }
    }

    private Response executeAuthorized(Request request) throws IOException, ApiException, JSONException {
        Response response = client.newCall(request).execute();
        if (response.code() != 401) {
            return response;
        }
        response.close();
        refreshSessionSync();
        Request retry = request.newBuilder()
                .header("Authorization", "Bearer " + sessionPreferences.getString(KEY_ACCESS_TOKEN, ""))
                .build();
        return client.newCall(retry).execute();
    }

    private void refreshSessionSync() throws IOException, JSONException, ApiException {
        String refresh = sessionPreferences.getString(KEY_REFRESH_TOKEN, "");
        if (refresh.isEmpty()) {
            throw new ApiException(401, "登录状态已失效");
        }
        JSONObject body = new JSONObject().put("refresh_token", refresh);
        Request request = new Request.Builder()
                .url(url("auth/refresh"))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            AccountSession session = parseSession(responseObject(response), true);
            saveSession(session);
        }
    }

    private Request.Builder authorized(Request.Builder builder) {
        return builder.header(
                "Authorization",
                "Bearer " + sessionPreferences.getString(KEY_ACCESS_TOKEN, "")
        );
    }

    private JSONObject responseObject(Response response) throws IOException, JSONException, ApiException {
        if (!response.isSuccessful()) {
            throw apiError(response);
        }
        ResponseBody body = response.body();
        return new JSONObject(body == null ? "{}" : body.string());
    }

    private ApiException apiError(Response response) throws IOException {
        ResponseBody body = response.body();
        String raw = body == null ? "" : body.string();
        String detail = raw;
        try {
            detail = new JSONObject(raw).optString("detail", raw);
        } catch (JSONException ignored) {
            // 保留原始服务器错误。
        }
        if (detail == null || detail.trim().isEmpty()) {
            detail = "服务器请求失败（" + response.code() + "）";
        }
        return new ApiException(response.code(), detail);
    }

    private void saveSession(AccountSession session) {
        QuoteRepository.setCurrentAccount(context, session.accountId);
        sessionPreferences.edit()
                .putString(KEY_ACCOUNT_ID, session.accountId)
                .putString(KEY_DISPLAY_NAME, session.displayName)
                .putString(KEY_PHONE, session.phone)
                .putString(KEY_ACCESS_TOKEN, session.accessToken)
                .putString(KEY_REFRESH_TOKEN, session.refreshToken)
                .putLong(KEY_ACCESS_EXPIRES, System.currentTimeMillis() + session.accessExpiresInSeconds * 1000L)
                .putLong(KEY_ACCOUNT_EXPIRES, session.expiresAtMillis)
                .putBoolean(KEY_MUST_CHANGE, session.mustChangePassword)
                .putBoolean(KEY_PROFILE_COMPLETED, session.profileCompleted)
                .putString(KEY_ACCOUNT_TYPE, session.accountType)
                .putString(KEY_TEAM_ID, session.teamId)
                .putString(KEY_TEAM_NAME, session.teamName)
                .putString(KEY_TEAM_ROLE, session.teamRole)
                .putString(KEY_TEAM_STATUS, session.teamStatus)
                .putInt(KEY_TEAM_VERSION, session.teamVersion)
                .putInt(KEY_QUOTE_CATALOG_VERSION, session.quoteCatalogVersion)
                .putBoolean(KEY_CAN_UPLOAD, session.canUploadQuotes)
                .apply();
    }

    private void clearSession() {
        sessionPreferences.edit().clear().apply();
    }

    private String url(String path) {
        return baseUrl + (path.startsWith("/") ? path.substring(1) : path);
    }

    private static long parseMillis(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        }
    }

    private static long parseOptionalMillis(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return 0L;
        }
        return parseMillis(value);
    }

    private static String shortDate(String value) {
        return value == null || value.length() < 10 ? value : value.substring(0, 10);
    }

    private String message(Exception exception) {
        if (exception instanceof ApiException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return "网络连接失败，请检查网络后重试";
        }
        return exception.getMessage() == null ? "操作失败，请重试" : exception.getMessage();
    }

    private <T> void success(ApiCallback<T> callback, T value) {
        mainHandler.post(() -> callback.onSuccess(value));
    }

    private void error(ApiCallback<?> callback, String value) {
        mainHandler.post(() -> callback.onError(value));
    }

    private static final class ApiException extends Exception {
        final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private final class ProgressTracker {
        private final long total;
        private final UploadProgressCallback callback;
        private long written;
        private int lastPercent = -1;

        ProgressTracker(long total, UploadProgressCallback callback) {
            this.total = total;
            this.callback = callback;
        }

        synchronized void add(long bytes) {
            written += bytes;
            int percent = Math.min(99, (int) (written * 100L / total));
            if (percent != lastPercent) {
                lastPercent = percent;
                mainHandler.post(() -> callback.onProgress(percent));
            }
        }
    }

    private static final class UriRequestBody extends RequestBody {
        private final Context context;
        private final QuoteUploadRequest.FilePart file;
        private final ProgressTracker tracker;

        UriRequestBody(Context context, QuoteUploadRequest.FilePart file, ProgressTracker tracker) {
            this.context = context;
            this.file = file;
            this.tracker = tracker;
        }

        @Override
        public MediaType contentType() {
            try {
                return MediaType.get(file.mimeType);
            } catch (IllegalArgumentException ignored) {
                return MediaType.get("application/octet-stream");
            }
        }

        @Override
        public long contentLength() {
            return file.sizeBytes;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            try (InputStream input = context.getContentResolver().openInputStream(file.contentUri)) {
                if (input == null) {
                    throw new IOException("文件读取失败：" + file.displayName);
                }
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    sink.write(buffer, 0, count);
                    tracker.add(count);
                }
            }
        }
    }
}
