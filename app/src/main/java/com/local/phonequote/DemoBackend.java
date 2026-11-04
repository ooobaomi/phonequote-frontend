package com.local.phonequote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DemoBackend implements AuthApi, QuoteTableApi, TeamApi {
    private static final String PREFS = "demo_backend";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_ACTIVE_TABLE = "active_table";
    private static final String KEY_UPLOADED_ID = "uploaded_id";
    private static final String KEY_UPLOADED_NAME = "uploaded_name";
    private static final String KEY_UPLOADED_DATE = "uploaded_date";
    private static final String DEFAULT_TABLE_ID = "local-20260730";

    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, UploadState> uploadStates = new LinkedHashMap<>();
    private final Map<String, QuoteTableInfo> uploadedTables = new LinkedHashMap<>();

    public DemoBackend(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restoreUploadedTable();
    }

    @Override
    public void sendRegisterCode(String phone, ApiCallback<Integer> callback) {
        handler.postDelayed(() -> callback.onSuccess(60), 250L);
    }

    @Override
    public void register(String name, String phone, String password, String code, ApiCallback<AccountSession> callback) {
        login(phone, password, callback);
    }

    @Override
    public void login(String account, String password, ApiCallback<AccountSession> callback) {
        handler.postDelayed(() -> {
            String normalizedAccount = account == null ? "" : account.trim();
            if (normalizedAccount.isEmpty() || password == null || password.trim().isEmpty()) {
                callback.onError("请输入账号和密码");
                return;
            }
            long expiresAt = System.currentTimeMillis() + 29L * 24L * 60L * 60L * 1000L
                    + 13L * 60L * 60L * 1000L;
            preferences.edit()
                    .putBoolean(KEY_LOGGED_IN, true)
                    .putString(KEY_ACCOUNT, normalizedAccount)
                    .putLong(KEY_EXPIRES_AT, expiresAt)
                    .apply();
            callback.onSuccess(session(normalizedAccount, expiresAt));
        }, 650L);
    }

    @Override
    public void getCurrentSession(ApiCallback<AccountSession> callback) {
        handler.postDelayed(() -> {
            if (!preferences.getBoolean(KEY_LOGGED_IN, false)) {
                callback.onError("登录状态已失效");
                return;
            }
            String account = preferences.getString(KEY_ACCOUNT, "demo_shop");
            long expiresAt = preferences.getLong(
                    KEY_EXPIRES_AT,
                    System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L
            );
            callback.onSuccess(session(account, expiresAt));
        }, 220L);
    }

    @Override
    public void logout(ApiCallback<Boolean> callback) {
        handler.postDelayed(() -> {
            preferences.edit().putBoolean(KEY_LOGGED_IN, false).apply();
            callback.onSuccess(true);
        }, 280L);
    }

    @Override
    public void changePassword(String currentPassword, String newPassword, ApiCallback<Boolean> callback) {
        handler.postDelayed(() -> callback.onSuccess(true), 250L);
    }

    @Override
    public void updateProfile(String name, ApiCallback<AccountSession> callback) {
        getCurrentSession(callback);
    }

    @Override
    public void getSupport(ApiCallback<SupportInfo> callback) {
        handler.postDelayed(() -> callback.onSuccess(new SupportInfo("", "demo-service", "请联系人工客服找回密码")), 200L);
    }

    @Override
    public void listTables(ApiCallback<List<QuoteTableInfo>> callback) {
        handler.postDelayed(() -> callback.onSuccess(currentTables()), 320L);
    }

    @Override
    public void uploadAndConvert(
            QuoteUploadRequest request,
            UploadProgressCallback progressCallback,
            ApiCallback<QuoteUploadJob> callback
    ) {
        handler.postDelayed(() -> {
            progressCallback.onProgress(100);
            String jobId = "job-" + UUID.randomUUID().toString();
            String tableId = "upload-" + System.currentTimeMillis();
            UploadState state = new UploadState(jobId, tableId, request, System.currentTimeMillis());
            uploadStates.put(jobId, state);
            callback.onSuccess(toJob(state));
        }, 450L);
    }

    @Override
    public void getUploadJob(String jobId, ApiCallback<QuoteUploadJob> callback) {
        handler.postDelayed(() -> {
            UploadState state = uploadStates.get(jobId);
            if (state == null) {
                callback.onError("上传任务不存在");
                return;
            }
            QuoteUploadJob job = toJob(state);
            if (job.status == QuoteUploadJob.Status.READY
                    && !uploadedTables.containsKey(state.tableId)) {
                uploadedTables.put(state.tableId, new QuoteTableInfo(
                        state.tableId,
                        tableName(state.request.tableName),
                        "用户上传 · 服务器 OCR",
                        today(),
                        job.itemCount,
                        job.brandSummary,
                        false,
                        QuoteTableInfo.Status.READY
                ));
                preferences.edit()
                        .putString(KEY_UPLOADED_ID, state.tableId)
                        .putString(KEY_UPLOADED_NAME, tableName(state.request.tableName))
                        .putString(KEY_UPLOADED_DATE, today())
                        .apply();
            }
            callback.onSuccess(job);
        }, 180L);
    }

    @Override
    public void activateTable(String tableId, ApiCallback<QuoteTableInfo> callback) {
        handler.postDelayed(() -> {
            QuoteTableInfo selected = findTable(tableId);
            if (selected == null) {
                callback.onError("报价表不存在或仍在识别中");
                return;
            }
            preferences.edit().putString(KEY_ACTIVE_TABLE, tableId).apply();
            callback.onSuccess(withActive(selected, true));
        }, 420L);
    }

    @Override
    public void selectTable(String tableId, ApiCallback<QuoteTableInfo> callback) {
        activateTable(tableId, callback);
    }

    @Override
    public void deselectTable(String tableId, ApiCallback<QuoteTableInfo> callback) {
        handler.postDelayed(() -> {
            QuoteTableInfo table = findTable(tableId);
            if (table == null) {
                callback.onError("报价表不存在");
                return;
            }
            if (tableId.equals(preferences.getString(KEY_ACTIVE_TABLE, ""))) {
                preferences.edit().remove(KEY_ACTIVE_TABLE).apply();
            }
            callback.onSuccess(withActive(table, false));
        }, 250L);
    }

    @Override
    public void updateSharing(String tableId, boolean sharedWithTeam, ApiCallback<QuoteTableInfo> callback) {
        QuoteTableInfo table = findTable(tableId);
        if (table == null) {
            callback.onError("报价表不存在");
        } else {
            callback.onSuccess(table);
        }
    }

    @Override
    public void getMyTeam(ApiCallback<TeamInfo> callback) {
        handler.postDelayed(() -> callback.onSuccess(demoTeam()), 200L);
    }

    @Override
    public void createTeam(String name, ApiCallback<TeamInfo> callback) {
        getMyTeam(callback);
    }

    @Override
    public void updateTeam(String name, Boolean allowMemberUploads, ApiCallback<TeamInfo> callback) {
        getMyTeam(callback);
    }

    @Override
    public void addTeamMember(String phone, ApiCallback<TeamInfo> callback) {
        getMyTeam(callback);
    }

    @Override
    public void removeTeamMember(String userId, ApiCallback<TeamInfo> callback) {
        getMyTeam(callback);
    }

    private TeamInfo demoTeam() {
        List<TeamInfo.Member> members = Arrays.asList(
                new TeamInfo.Member("demo-user-001", "演示负责人", "138****0000", "OWNER", "ACTIVE", System.currentTimeMillis()),
                new TeamInfo.Member("demo-user-002", "城北门店", "139****0001", "MEMBER", "ACTIVE", System.currentTimeMillis())
        );
        return new TeamInfo("TEAM_OWNER", false, "demo-team", "演示回收团队", "ACTIVE",
                10, members.size(), true, 1, "demo-user-001", "演示负责人", 1, members);
    }

    @Override
    public void deleteLocalTable(QuoteTableInfo table, ApiCallback<Boolean> callback) {
        handler.postDelayed(() -> {
            uploadedTables.remove(table.id);
            if (table.id.equals(preferences.getString(KEY_ACTIVE_TABLE, ""))) {
                preferences.edit().remove(KEY_ACTIVE_TABLE).apply();
            }
            callback.onSuccess(true);
        }, 250L);
    }

    @Override
    public void deleteCloudTable(QuoteTableInfo table, ApiCallback<Boolean> callback) {
        handler.postDelayed(() -> callback.onSuccess(true), 250L);
    }

    @Override
    public void downloadAndImport(QuoteUploadJob job, ApiCallback<QuoteUploadJob> callback) {
        handler.postDelayed(() -> callback.onSuccess(new QuoteUploadJob(
                job.id, job.tableId, QuoteUploadJob.Status.IMPORTED, 100,
                "报价表已下载并导入", job.itemCount, job.brandSummary,
                job.previewRows, job.revision, job.sha256
        )), 450L);
    }

    private AccountSession session(String account, long expiresAt) {
        return new AccountSession(
                "demo-user-001",
                "演示负责人",
                "演示回收门店",
                maskAccount(account),
                "DEMO_ACCESS_TOKEN",
                "DEMO_REFRESH_TOKEN",
                900,
                expiresAt,
                false,
                true,
                "TEAM_OWNER",
                "demo-team",
                "演示回收团队",
                "OWNER",
                "ACTIVE",
                1,
                1,
                true
        );
    }

    private List<QuoteTableInfo> currentTables() {
        String activeId = preferences.getString(KEY_ACTIVE_TABLE, DEFAULT_TABLE_ID);
        List<QuoteTableInfo> tables = new ArrayList<>();
        tables.add(new QuoteTableInfo(
                DEFAULT_TABLE_ID,
                "小米 / 华为回收报价",
                "本地品牌隔离库",
                "2026-07-30",
                322,
                "小米 145 · 华为 177",
                DEFAULT_TABLE_ID.equals(activeId),
                QuoteTableInfo.Status.READY
        ));
        tables.add(new QuoteTableInfo(
                "cloud-july-shop",
                "门店七月报价表",
                "服务器转换 · Excel",
                "2026-07-28",
                286,
                "苹果 102 · 小米 84 · 华为 100",
                "cloud-july-shop".equals(activeId),
                QuoteTableInfo.Status.READY
        ));
        tables.add(new QuoteTableInfo(
                "cloud-backup",
                "备用回收报价表",
                "服务器转换 · 图片",
                "2026-07-20",
                198,
                "小米 91 · 华为 107",
                "cloud-backup".equals(activeId),
                QuoteTableInfo.Status.READY
        ));
        for (QuoteTableInfo table : uploadedTables.values()) {
            tables.add(withActive(table, table.id.equals(activeId)));
        }
        return tables;
    }

    private QuoteTableInfo findTable(String tableId) {
        for (QuoteTableInfo table : currentTables()) {
            if (table.id.equals(tableId) && table.status == QuoteTableInfo.Status.READY) {
                return table;
            }
        }
        return null;
    }

    private QuoteTableInfo withActive(QuoteTableInfo table, boolean active) {
        return new QuoteTableInfo(
                table.id,
                table.name,
                table.sourceType,
                table.updatedAt,
                table.itemCount,
                table.brandSummary,
                active,
                table.status
        );
    }

    private QuoteUploadJob toJob(UploadState state) {
        long elapsed = System.currentTimeMillis() - state.startedAt;
        if (elapsed < 900L) {
            return job(state, QuoteUploadJob.Status.UPLOADING, 18, "正在上传报价表", 0, "");
        }
        if (elapsed < 1900L) {
            return job(state, QuoteUploadJob.Status.OCR, 52, "服务器正在识别品牌、型号和价格", 0, "");
        }
        if (elapsed < 2900L) {
            return job(state, QuoteUploadJob.Status.NORMALIZING, 82, "正在校验并转换为标准报价格式", 0, "");
        }
        return job(state, QuoteUploadJob.Status.READY, 100, "识别转换完成", 326, "小米 146 · 华为 180");
    }

    private QuoteUploadJob job(
            UploadState state,
            QuoteUploadJob.Status status,
            int progress,
            String message,
            int count,
            String brands
    ) {
        List<String> preview = status == QuoteUploadJob.Status.READY
                ? Arrays.asList(
                        "小米 14 5G · 16+512 · 6 档价格",
                        "小米 14 Pro 5G · 16+1TB · 6 档价格",
                        "华为 Mate 70 Pro · 12+512 · 6 档价格"
                )
                : Collections.emptyList();
        return new QuoteUploadJob(
                state.jobId,
                state.tableId,
                status,
                progress,
                message,
                count,
                brands,
                preview
        );
    }

    private String tableName(String fileName) {
        String value = fileName == null || fileName.trim().isEmpty() ? "新报价表" : fileName.trim();
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        return value + "（已转换）";
    }

    private void restoreUploadedTable() {
        String id = preferences.getString(KEY_UPLOADED_ID, "");
        if (id == null || id.isEmpty()) {
            return;
        }
        String name = preferences.getString(KEY_UPLOADED_NAME, "用户上传报价表（已转换）");
        String updated = preferences.getString(KEY_UPLOADED_DATE, today());
        uploadedTables.put(id, new QuoteTableInfo(
                id,
                name,
                "用户上传 · 服务器 OCR",
                updated,
                326,
                "小米 146 · 华为 180",
                false,
                QuoteTableInfo.Status.READY
        ));
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    private String maskAccount(String account) {
        if (account.length() <= 4) {
            return account;
        }
        return account.substring(0, 3) + "****" + account.substring(account.length() - 2);
    }

    private static final class UploadState {
        final String jobId;
        final String tableId;
        final QuoteUploadRequest request;
        final long startedAt;

        UploadState(String jobId, String tableId, QuoteUploadRequest request, long startedAt) {
            this.jobId = jobId;
            this.tableId = tableId;
            this.request = request;
            this.startedAt = startedAt;
        }
    }
}
