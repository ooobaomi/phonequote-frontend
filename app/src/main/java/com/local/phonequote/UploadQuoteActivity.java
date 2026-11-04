package com.local.phonequote;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class UploadQuoteActivity extends Activity {
    private static final int REQUEST_FILE = 201;
    private static final int MAX_FILES = 30;
    private static final long MAX_BYTES = 200L * 1024L * 1024L;
    private static final String PREFS = "quote_upload_state";
    private static final String KEY_JOB_ID = "active_job_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView fileNameText;
    private TextView fileMetaText;
    private Button startUploadButton;
    private LinearLayout progressPanel;
    private TextView stageText;
    private ProgressBar uploadProgress;
    private LinearLayout resultPanel;
    private TextView resultSummary;
    private LinearLayout previewRows;
    private Button activateButton;
    private QuoteUploadRequest selectedRequest;
    private String currentJobId;
    private boolean downloading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload_quote);
        fileNameText = findViewById(R.id.fileNameText);
        fileMetaText = findViewById(R.id.fileMetaText);
        startUploadButton = findViewById(R.id.startUploadButton);
        progressPanel = findViewById(R.id.progressPanel);
        stageText = findViewById(R.id.stageText);
        uploadProgress = findViewById(R.id.uploadProgress);
        resultPanel = findViewById(R.id.resultPanel);
        resultSummary = findViewById(R.id.resultSummary);
        previewRows = findViewById(R.id.previewRows);
        activateButton = findViewById(R.id.activateButton);

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.chooseFileButton).setOnClickListener(view -> chooseFile());
        startUploadButton.setOnClickListener(view -> startUpload());
        activateButton.setOnClickListener(view -> openQuoteTables());

        currentJobId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_JOB_ID, null);
        if (currentJobId != null && !currentJobId.isEmpty()) {
            progressPanel.setVisibility(View.VISIBLE);
            startUploadButton.setEnabled(false);
            stageText.setText("正在恢复上次转换任务...");
            pollJob();
        }
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "image/*", "application/pdf", "application/json", "text/csv",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        });
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                uris.add(clipData.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        if (uris.size() > MAX_FILES) {
            showError("一次最多选择 30 个文件");
            return;
        }

        List<QuoteUploadRequest.FilePart> files = new ArrayList<>();
        long total = 0L;
        StringBuilder names = new StringBuilder();
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // 当前进程仍保有临时读取权限。
            }
            QuoteUploadRequest.FilePart file = readMetadata(uri);
            files.add(file);
            if (file.sizeBytes > 0) {
                total += file.sizeBytes;
            }
            if (files.size() <= 4) {
                if (names.length() > 0) {
                    names.append("\n");
                }
                names.append("• ").append(file.displayName);
            }
        }
        if (total > MAX_BYTES) {
            showError("所选文件总大小不能超过 200MB");
            return;
        }
        String tableName = stripExtension(files.get(0).displayName)
                + (files.size() > 1 ? " 等 " + files.size() + " 个文件" : "");
        selectedRequest = new QuoteUploadRequest(tableName, files);
        fileNameText.setText("已选择 " + files.size() + " 个文件");
        fileMetaText.setText(names + "\n总大小：" + formatSize(total));
        startUploadButton.setEnabled(true);
        resultPanel.setVisibility(View.GONE);
    }

    private QuoteUploadRequest.FilePart readMetadata(Uri uri) {
        String name = "报价表文件";
        long size = -1L;
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) {
                    name = cursor.getString(nameColumn);
                }
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn);
                }
            }
        }
        String mimeType = getContentResolver().getType(uri);
        return new QuoteUploadRequest.FilePart(
                uri, name, mimeType == null ? "application/octet-stream" : mimeType, size
        );
    }

    private void startUpload() {
        if (selectedRequest == null) {
            return;
        }
        startUploadButton.setEnabled(false);
        progressPanel.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        uploadProgress.setProgress(2);
        stageText.setText("正在上传报价表 · 0%");
        AppServices.quoteTables(this).uploadAndConvert(
                selectedRequest,
                percent -> {
                    uploadProgress.setProgress(Math.min(20, 2 + percent * 18 / 100));
                    stageText.setText("正在上传报价表 · " + percent + "%");
                },
                new ApiCallback<QuoteUploadJob>() {
                    @Override
                    public void onSuccess(QuoteUploadJob value) {
                        currentJobId = value.id;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_JOB_ID, currentJobId).apply();
                        renderJob(value);
                        schedulePoll(value.status == QuoteUploadJob.Status.REVIEW_REQUIRED ? 5000L : 1800L);
                    }

                    @Override
                    public void onError(String message) {
                        showError(message);
                        startUploadButton.setEnabled(true);
                    }
                }
        );
    }

    private void schedulePoll(long delay) {
        handler.postDelayed(this::pollJob, delay);
    }

    private void pollJob() {
        if (currentJobId == null || isFinishing()) {
            return;
        }
        AppServices.quoteTables(this).getUploadJob(currentJobId, new ApiCallback<QuoteUploadJob>() {
            @Override
            public void onSuccess(QuoteUploadJob value) {
                renderJob(value);
                if (value.status == QuoteUploadJob.Status.READY) {
                    downloadAndImport(value);
                } else if (value.status != QuoteUploadJob.Status.FAILED
                        && value.status != QuoteUploadJob.Status.CANCELLED
                        && value.status != QuoteUploadJob.Status.IMPORTED) {
                    schedulePoll(value.status == QuoteUploadJob.Status.REVIEW_REQUIRED ? 5000L : 1800L);
                } else {
                    startUploadButton.setEnabled(true);
                }
            }

            @Override
            public void onError(String message) {
                showError(message);
                schedulePoll(5000L);
            }
        });
    }

    private void downloadAndImport(QuoteUploadJob job) {
        if (downloading) {
            return;
        }
        downloading = true;
        uploadProgress.setProgress(94);
        stageText.setText("转换完成，正在下载并校验报价表...");
        AppServices.quoteTables(this).downloadAndImport(job, new ApiCallback<QuoteUploadJob>() {
            @Override
            public void onSuccess(QuoteUploadJob value) {
                downloading = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_JOB_ID).apply();
                currentJobId = null;
                renderJob(value);
                Toast.makeText(
                        UploadQuoteActivity.this,
                        "报价表已下载并导入成功，请手动切换报价表。",
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(String message) {
                downloading = false;
                uploadProgress.setProgress(90);
                stageText.setText("下载或导入失败：" + message);
                startUploadButton.setEnabled(true);
            }
        });
    }

    private void renderJob(QuoteUploadJob job) {
        progressPanel.setVisibility(View.VISIBLE);
        uploadProgress.setProgress(job.status == QuoteUploadJob.Status.IMPORTED ? 100 : job.progress);
        stageText.setText(job.message + " · " + job.progress + "%");
        if (job.status == QuoteUploadJob.Status.REVIEW_REQUIRED) {
            stageText.setText("识别完成，部分数据正在等待后台人工核对 · 90%");
        }
        if (job.status != QuoteUploadJob.Status.READY
                && job.status != QuoteUploadJob.Status.IMPORTED) {
            return;
        }
        resultPanel.setVisibility(View.VISIBLE);
        String headline = job.status == QuoteUploadJob.Status.IMPORTED
                ? "已下载并导入 " + job.itemCount + " 条型号配置\n请手动切换报价表"
                : "已转换 " + job.itemCount + " 条型号配置\n正在自动下载";
        resultSummary.setText(headline + (job.brandSummary.isEmpty() ? "" : "\n" + job.brandSummary));
        previewRows.removeAllViews();
        for (String row : job.previewRows) {
            TextView text = new TextView(this);
            text.setText("• " + row);
            text.setTextColor(getColor(R.color.muted));
            text.setTextSize(13f);
            text.setPadding(0, dp(5), 0, 0);
            previewRows.addView(text);
        }
        activateButton.setVisibility(job.status == QuoteUploadJob.Status.IMPORTED ? View.VISIBLE : View.GONE);
        activateButton.setText("去报价表管理并手动切换");
        startUploadButton.setEnabled(job.status == QuoteUploadJob.Status.IMPORTED);
    }

    private void openQuoteTables() {
        Intent intent = new Intent(this, QuoteTableActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        stageText.setText(message);
        progressPanel.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String formatSize(long bytes) {
        if (bytes < 0) {
            return "大小未知";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024d);
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / (1024d * 1024d));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

