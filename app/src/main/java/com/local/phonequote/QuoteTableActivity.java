package com.local.phonequote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.ArrayList;

public final class QuoteTableActivity extends Activity {
    private LinearLayout tableList;
    private ProgressBar tableProgress;
    private TextView tableError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quote_tables);

        tableList = findViewById(R.id.tableList);
        tableProgress = findViewById(R.id.tableProgress);
        tableError = findViewById(R.id.tableError);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.uploadButton).setOnClickListener(view ->
                startActivity(new Intent(this, UploadQuoteActivity.class))
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTables();
    }

    private void loadTables() {
        tableProgress.setVisibility(View.VISIBLE);
        tableError.setVisibility(View.GONE);
        AppServices.quoteTables(this).listTables(new ApiCallback<List<QuoteTableInfo>>() {
            @Override
            public void onSuccess(List<QuoteTableInfo> value) {
                tableProgress.setVisibility(View.GONE);
                renderTables(value);
            }

            @Override
            public void onError(String message) {
                tableProgress.setVisibility(View.GONE);
                tableError.setText(message);
                tableError.setVisibility(View.VISIBLE);
            }
        });
    }

    private void renderTables(List<QuoteTableInfo> tables) {
        tableList.removeAllViews();
        for (QuoteTableInfo table : tables) {
            tableList.addView(createTableCard(table));
        }
    }

    private View createTableCard(QuoteTableInfo table) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(table.active ? R.drawable.bg_success_banner : R.drawable.bg_panel);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        TextView title = text(table.name, 17f, R.color.ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView state = text(
                table.active ? "● 已加入使用 · 优先级 " + table.selectionOrder : statusLabel(table),
                13f,
                table.active ? R.color.green_dark : R.color.muted
        );
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stateParams.topMargin = dp(5);
        state.setLayoutParams(stateParams);
        card.addView(state);

        TextView meta = text(
                table.sourceType + " · " + table.itemCount + " 条\n"
                        + table.brandSummary + "\n版本：v" + table.revision
                        + " · 更新：" + table.updatedAt + "\n" + cloudLabel(table),
                13f,
                R.color.muted
        );
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metaParams.topMargin = dp(8);
        meta.setLayoutParams(metaParams);
        card.addView(meta);

        if (QuoteRepository.LOCAL_ONLY_TABLE_ID.equals(table.id)) {
            TextView localWarning = text("此报价表没有云端副本，只支持继续使用或永久删除。", 13f, R.color.danger);
            card.addView(localWarning);
        } else if (table.active) {
            card.addView(actionButton("设为最高优先级", view -> select(table, (Button) view)));
            card.addView(actionButton("取消使用", view -> deselect(table, (Button) view)));
        } else {
            Button select = actionButton(
                    table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                            ? "下载并加入使用" : "加入使用",
                    view -> select(table, (Button) view)
            );
            select.setEnabled(table.revision > 0
                    && (table.cloudAvailable || table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED));
            card.addView(select);
        }
        boolean hasLocalCopy = table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED
                && table.localState != QuoteTableInfo.LocalState.DOWNLOADING;
        boolean ownsCloud = "OWN".equals(table.accessScope)
                && !QuoteRepository.LOCAL_ONLY_TABLE_ID.equals(table.id)
                && table.localState != QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED;
        if (hasLocalCopy || ownsCloud) {
            Button delete = actionButton("删除报价表", view -> showDeleteChoices(table));
            delete.setTextColor(getColor(R.color.danger));
            card.addView(delete);
        }
        return card;
    }

    private Button actionButton(String label, View.OnClickListener listener) {
        Button action = new Button(this);
        action.setAllCaps(false);
        action.setText(label);
        action.setTextColor(getColor(R.color.blue));
        action.setBackgroundResource(R.drawable.bg_button_secondary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(10);
        action.setLayoutParams(params);
        action.setOnClickListener(listener);
        return action;
    }

    private void select(QuoteTableInfo table, Button action) {
        action.setEnabled(false);
        action.setText(table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                ? "正在下载并加入..." : "正在更新优先级...");
        AppServices.quoteTables(this).selectTable(
                table.id,
                new ApiCallback<QuoteTableInfo>() {
                    @Override
                    public void onSuccess(QuoteTableInfo value) {
                        Toast.makeText(
                                QuoteTableActivity.this,
                                "已加入使用并设为最高优先级：" + value.name,
                                Toast.LENGTH_SHORT
                        ).show();
                        loadTables();
                    }

                    @Override
                    public void onError(String message) {
                        action.setEnabled(true);
                        action.setText("加入使用");
                        Toast.makeText(QuoteTableActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void deselect(QuoteTableInfo table, Button action) {
        action.setEnabled(false);
        action.setText("正在取消...");
        AppServices.quoteTables(this).deselectTable(table.id, new ApiCallback<QuoteTableInfo>() {
            @Override
            public void onSuccess(QuoteTableInfo value) {
                Toast.makeText(QuoteTableActivity.this, "已停止使用：" + table.name, Toast.LENGTH_SHORT).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                action.setEnabled(true);
                action.setText("取消使用");
                Toast.makeText(QuoteTableActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showDeleteChoices(QuoteTableInfo table) {
        List<String> choices = new ArrayList<>();
        boolean hasLocalCopy = table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED
                && table.localState != QuoteTableInfo.LocalState.DOWNLOADING;
        boolean ownsCloud = "OWN".equals(table.accessScope)
                && !QuoteRepository.LOCAL_ONLY_TABLE_ID.equals(table.id)
                && table.localState != QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED;
        if (hasLocalCopy) choices.add("删除本地报价表");
        if (ownsCloud) choices.add("删除云端报价表");
        new AlertDialog.Builder(this)
                .setTitle("删除报价表")
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if ("删除本地报价表".equals(choices.get(which))) confirmDeleteLocal(table);
                    else confirmDeleteCloud(table);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteLocal(QuoteTableInfo table) {
        new AlertDialog.Builder(this)
                .setTitle("删除本地报价表")
                .setMessage(table.cloudAvailable
                        ? "将停止使用并清除本机副本，云端有效期内可以重新下载。"
                        : "这份报价只保存在本机，删除后将失去本机报价数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除本地", (dialog, which) -> deleteLocal(table))
                .show();
    }

    private void confirmDeleteCloud(QuoteTableInfo table) {
        boolean hasLocalCopy = table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED;
        new AlertDialog.Builder(this)
                .setTitle("删除云端报价表")
                .setMessage("云端报价、团队共享和成员选择将被清除。"
                        + (hasLocalCopy ? "本机副本会保留并标记为仅本地。" : "本机没有已下载副本。"))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除云端", (dialog, which) -> deleteCloud(table))
                .show();
    }

    private void deleteLocal(QuoteTableInfo table) {
        AppServices.quoteTables(this).deleteLocalTable(table, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                Toast.makeText(QuoteTableActivity.this,
                        table.cloudAvailable ? "本地报价表已删除，可重新下载" : "本地报价表已删除",
                        Toast.LENGTH_LONG).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(QuoteTableActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void deleteCloud(QuoteTableInfo table) {
        AppServices.quoteTables(this).deleteCloudTable(table, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                Toast.makeText(QuoteTableActivity.this,
                        table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                                ? "云端报价表已删除"
                                : "云端报价表已删除，本机副本已保留",
                        Toast.LENGTH_LONG).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(QuoteTableActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String cloudLabel(QuoteTableInfo table) {
        if (table.localState == QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED) {
            return "云端已删除 · 仅本机保留";
        }
        if (table.localState == QuoteTableInfo.LocalState.LOCAL_ONLY_MANUAL) {
            return "纯本地报价 · 无云端副本";
        }
        if (!table.cloudAvailable) {
            return "云端已过期 · " + (table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                    ? "本机无副本" : "仅本机保留");
        }
        long remaining = Math.max(0L, table.cloudExpiresAtMillis - System.currentTimeMillis());
        long hours = remaining / (60L * 60L * 1000L);
        long minutes = (remaining / (60L * 1000L)) % 60L;
        return "云端可下载剩余 " + hours + " 小时 " + minutes + " 分";
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        return view;
    }

    private String statusLabel(QuoteTableInfo table) {
        if (table.status == QuoteTableInfo.Status.PROCESSING) {
            return "识别转换中";
        }
        if (table.status == QuoteTableInfo.Status.FAILED) {
            return "转换失败";
        }
        if (table.localState == QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED) {
            return "云端已删除，仅本机保留";
        }
        if (table.localState == QuoteTableInfo.LocalState.LOCAL_ONLY_MANUAL) {
            return "仅本机保存";
        }
        if (table.status == QuoteTableInfo.Status.EXPIRED) {
            return table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                    ? "云端已过期，本机无副本" : "云端已过期，仅本机保留";
        }
        return table.localState == QuoteTableInfo.LocalState.IMPORTED
                ? "已下载，可加入使用" : "服务器已发布，尚未下载";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
