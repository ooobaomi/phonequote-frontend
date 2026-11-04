package com.local.phonequote;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RecognitionFragment extends Fragment {
    private TextView statusText;
    private TextView dataBanner;
    private TextView dataDetail;
    private TextView activeTableName;
    private TextView activeTableMeta;
    private Button startButton;
    private Button stopButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_recognition, container, false);
        statusText = view.findViewById(R.id.statusText);
        dataBanner = view.findViewById(R.id.dataBanner);
        dataDetail = view.findViewById(R.id.dataDetail);
        activeTableName = view.findViewById(R.id.activeTableName);
        activeTableMeta = view.findViewById(R.id.activeTableMeta);
        startButton = view.findViewById(R.id.startButton);
        stopButton = view.findViewById(R.id.stopButton);
        startButton.setOnClickListener(value -> main().startAssistant());
        stopButton.setOnClickListener(value -> main().stopAssistant());
        view.findViewById(R.id.importButton).setOnClickListener(value -> main().chooseQuoteFile());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    public void refresh() {
        if (!isAdded()) return;
        refreshRunState();
        refreshDataSummary();
        refreshActiveTables();
    }

    public void showRunState(String value) {
        if (statusText != null) {
            statusText.setText(value);
            boolean running = CaptureService.isRunning();
            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
        }
    }

    private void refreshRunState() {
        if (startButton == null) return;
        boolean running = CaptureService.isRunning();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        statusText.setText(running ? "悬浮识别正在运行" : "悬浮识别未启动");
    }

    private void refreshDataSummary() {
        try {
            QuoteRepository repository = new QuoteRepository(requireContext());
            dataBanner.setText(repository.isImported() ? "本地报价数据已就绪" : "当前使用内置示例报价");
            dataDetail.setText(repository.getSourceName() + " · " + repository.getItems().size()
                    + " 条\n更新日期：" + repository.getUpdatedAt() + "\n" + repository.getBrandSummary());
        } catch (IOException | JSONException exception) {
            dataBanner.setText("报价库读取失败");
            dataDetail.setText(exception.getMessage());
        }
    }

    private void refreshActiveTables() {
        AppServices.quoteTables(requireContext()).listTables(new ApiCallback<List<QuoteTableInfo>>() {
            @Override
            public void onSuccess(List<QuoteTableInfo> tables) {
                if (!isAdded()) return;
                List<QuoteTableInfo> active = new ArrayList<>();
                for (QuoteTableInfo table : tables) if (table.active) active.add(table);
                active.sort((left, right) -> Integer.compare(right.selectionOrder, left.selectionOrder));
                if (active.isEmpty()) {
                    activeTableName.setText("尚未选择报价表");
                    activeTableMeta.setText("请在“报价表”底栏加入至少一份报价表");
                    return;
                }
                StringBuilder names = new StringBuilder();
                for (QuoteTableInfo table : active) {
                    if (names.length() > 0) names.append(" → ");
                    names.append(table.name);
                }
                activeTableName.setText("已启用 " + active.size() + " 份报价表");
                activeTableMeta.setText("后选优先：" + names);
            }

            @Override
            public void onError(String message) {
                if (activeTableName != null) {
                    activeTableName.setText("报价表读取失败");
                    activeTableMeta.setText(message);
                }
            }
        });
    }

    private MainActivity main() {
        return (MainActivity) requireActivity();
    }
}
