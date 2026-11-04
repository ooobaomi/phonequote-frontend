package com.local.phonequote;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

public final class QuoteTablesFragment extends Fragment {
    private LinearLayout tableList;
    private ProgressBar tableProgress;
    private TextView tableError;
    private boolean hasTeam;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.activity_quote_tables, container, false);
        view.findViewById(R.id.backButton).setVisibility(View.GONE);
        view.findViewById(R.id.uploadButton).setVisibility(View.GONE);
        tableList = view.findViewById(R.id.tableList);
        tableProgress = view.findViewById(R.id.tableProgress);
        tableError = view.findViewById(R.id.tableError);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        AppServices.auth(requireContext()).getCurrentSession(new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession session) {
                hasTeam = session.teamId != null && !session.teamId.isEmpty();
                loadTables();
            }

            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void loadTables() {
        tableProgress.setVisibility(View.VISIBLE);
        tableError.setVisibility(View.GONE);
        AppServices.quoteTables(requireContext()).listTables(new ApiCallback<List<QuoteTableInfo>>() {
            @Override
            public void onSuccess(List<QuoteTableInfo> value) {
                if (!isAdded()) return;
                tableProgress.setVisibility(View.GONE);
                renderTables(value);
            }

            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showError(String message) {
        if (!isAdded() || tableProgress == null) return;
        tableProgress.setVisibility(View.GONE);
        tableError.setText(message);
        tableError.setVisibility(View.VISIBLE);
    }

    private void renderTables(List<QuoteTableInfo> tables) {
        tableList.removeAllViews();
        List<QuoteTableInfo> own = new ArrayList<>();
        List<QuoteTableInfo> shared = new ArrayList<>();
        for (QuoteTableInfo table : tables) {
            if ("TEAM".equals(table.accessScope)) shared.add(table); else own.add(table);
        }
        addSection("我的报价", own, "还没有私人报价表，请从“上传”底栏添加。\n");
        if (hasTeam) addSection("团队共享", shared, "团队暂时没有共享报价表。\n");
    }

    private void addSection(String titleValue, List<QuoteTableInfo> tables, String emptyText) {
        TextView title = text(titleValue, 18f, R.color.ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(10));
        tableList.addView(title);
        if (tables.isEmpty()) {
            TextView empty = text(emptyText, 13f, R.color.muted);
            empty.setPadding(0, 0, 0, dp(12));
            tableList.addView(empty);
            return;
        }
        for (QuoteTableInfo table : tables) tableList.addView(createTableCard(table));
    }

    private View createTableCard(QuoteTableInfo table) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(table.active ? R.drawable.bg_success_banner : R.drawable.bg_panel);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        TextView title = text(table.name, 17f, R.color.ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        String ownerLine = table.uploaderName.isEmpty() ? "" : " · 上传者 " + table.uploaderName;
        TextView state = text(
                (table.active ? "● 已加入使用 · 优先级 " + table.selectionOrder : statusLabel(table))
                        + (table.sharedWithTeam ? " · 团队共享" : "") + ownerLine,
                13f,
                table.active ? R.color.green_dark : R.color.muted
        );
        state.setPadding(0, dp(5), 0, 0);
        card.addView(state);

        TextView meta = text(table.sourceType + " · " + table.itemCount + " 条\n"
                        + table.brandSummary + "\n版本：v" + table.revision
                        + " · 更新：" + table.updatedAt + "\n" + cloudLabel(table),
                13f, R.color.muted);
        meta.setPadding(0, dp(8), 0, 0);
        card.addView(meta);

        if (QuoteRepository.LOCAL_ONLY_TABLE_ID.equals(table.id)) {
            card.addView(text("此报价表没有云端副本，只支持继续使用或永久删除。", 13f, R.color.danger));
        } else if (table.active) {
            card.addView(actionButton("设为最高优先级", view -> select(table, (Button) view)));
            card.addView(actionButton("取消使用", view -> deselect(table, (Button) view)));
        } else {
            Button select = actionButton(
                    table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED ? "下载并加入使用" : "加入使用",
                    view -> select(table, (Button) view)
            );
            select.setEnabled(table.revision > 0
                    && (table.cloudAvailable || table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED));
            card.addView(select);
        }
        if (table.canManageSharing && (table.sharedWithTeam || hasTeam)) {
            Button sharing = actionButton(
                    table.sharedWithTeam ? "取消团队共享" : "共享到团队",
                    view -> updateSharing(table, !table.sharedWithTeam, (Button) view)
            );
            card.addView(sharing);
        }
        boolean hasLocalCopy = table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED
                && table.localState != QuoteTableInfo.LocalState.DOWNLOADING;
        boolean ownsCloud = "OWN".equals(table.accessScope)
                && !QuoteRepository.LOCAL_ONLY_TABLE_ID.equals(table.id)
                && table.localState != QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED;
        if (hasLocalCopy || ownsCloud) {
            Button delete = actionButton("删除报价表", view -> showDeleteChoices(table));
            delete.setTextColor(requireContext().getColor(R.color.danger));
            card.addView(delete);
        }
        return card;
    }

    private Button actionButton(String label, View.OnClickListener listener) {
        Button action = new Button(requireContext());
        action.setAllCaps(false);
        action.setText(label);
        action.setTextColor(requireContext().getColor(R.color.blue));
        action.setBackgroundResource(R.drawable.bg_button_secondary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(9);
        action.setLayoutParams(params);
        action.setOnClickListener(listener);
        return action;
    }

    private void select(QuoteTableInfo table, Button button) {
        button.setEnabled(false);
        button.setText(table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                ? "正在下载并加入..." : "正在更新优先级...");
        AppServices.quoteTables(requireContext()).selectTable(table.id, new ApiCallback<QuoteTableInfo>() {
            @Override
            public void onSuccess(QuoteTableInfo value) {
                Toast.makeText(requireContext(), "已加入并设为最高优先级：" + value.name, Toast.LENGTH_SHORT).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                button.setEnabled(true);
                button.setText("加入使用");
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void deselect(QuoteTableInfo table, Button button) {
        button.setEnabled(false);
        AppServices.quoteTables(requireContext()).deselectTable(table.id, new ApiCallback<QuoteTableInfo>() {
            @Override
            public void onSuccess(QuoteTableInfo value) {
                Toast.makeText(requireContext(), "已停止使用：" + table.name, Toast.LENGTH_SHORT).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                button.setEnabled(true);
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateSharing(QuoteTableInfo table, boolean shared, Button button) {
        button.setEnabled(false);
        AppServices.quoteTables(requireContext()).updateSharing(table.id, shared, new ApiCallback<QuoteTableInfo>() {
            @Override
            public void onSuccess(QuoteTableInfo value) {
                Toast.makeText(requireContext(), shared ? "已共享到团队" : "已取消团队共享", Toast.LENGTH_SHORT).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                button.setEnabled(true);
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
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
        new AlertDialog.Builder(requireContext())
                .setTitle("删除报价表")
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if ("删除本地报价表".equals(choices.get(which))) {
                        confirmDeleteLocal(table);
                    } else {
                        confirmDeleteCloud(table);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteLocal(QuoteTableInfo table) {
        boolean recoverable = table.cloudAvailable;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除本地报价表")
                .setMessage(recoverable
                        ? "将停止使用并清除本机副本，云端有效期内可以重新下载。"
                        : "这份报价只保存在本机，删除后将失去本机报价数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除本地", (dialog, which) -> deleteLocal(table))
                .show();
    }

    private void confirmDeleteCloud(QuoteTableInfo table) {
        boolean hasLocalCopy = table.localState != QuoteTableInfo.LocalState.NOT_DOWNLOADED;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除云端报价表")
                .setMessage("云端报价、团队共享和成员选择将被清除。"
                        + (hasLocalCopy ? "本机副本会保留并标记为仅本地。" : "本机没有已下载副本。"))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除云端", (dialog, which) -> deleteCloud(table))
                .show();
    }

    private void deleteLocal(QuoteTableInfo table) {
        AppServices.quoteTables(requireContext()).deleteLocalTable(table, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                Toast.makeText(requireContext(), table.cloudAvailable
                        ? "本地报价表已删除，可重新下载" : "本地报价表已删除", Toast.LENGTH_LONG).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void deleteCloud(QuoteTableInfo table) {
        AppServices.quoteTables(requireContext()).deleteCloudTable(table, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                Toast.makeText(requireContext(),
                        table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                                ? "云端报价表已删除"
                                : "云端报价表已删除，本机副本已保留",
                        Toast.LENGTH_LONG).show();
                loadTables();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
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
        if (!table.cloudAvailable) return "云端已过期 · "
                + (table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED ? "本机无副本" : "仅本机保留");
        long remaining = Math.max(0L, table.cloudExpiresAtMillis - System.currentTimeMillis());
        return "云端可下载剩余 " + remaining / 3600000L + " 小时 " + (remaining / 60000L) % 60L + " 分";
    }

    private String statusLabel(QuoteTableInfo table) {
        if (table.status == QuoteTableInfo.Status.PROCESSING) return "识别转换中";
        if (table.status == QuoteTableInfo.Status.FAILED) return "转换失败";
        if (table.localState == QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED) return "云端已删除，仅本机保留";
        if (table.localState == QuoteTableInfo.LocalState.LOCAL_ONLY_MANUAL) return "仅本机保存";
        if (table.status == QuoteTableInfo.Status.EXPIRED) return table.localState == QuoteTableInfo.LocalState.NOT_DOWNLOADED
                ? "云端已过期，本机无副本" : "云端已过期，仅本机保留";
        return table.localState == QuoteTableInfo.LocalState.IMPORTED ? "已下载，可加入使用" : "服务器已发布，尚未下载";
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(requireContext().getColor(color));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
