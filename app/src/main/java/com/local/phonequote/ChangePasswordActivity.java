package com.local.phonequote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public final class ChangePasswordActivity extends Activity {
    public static final String EXTRA_FORCED = "forced";
    private EditText currentInput;
    private EditText newInput;
    private EditText confirmInput;
    private TextView errorText;
    private Button submitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);
        currentInput = findViewById(R.id.currentPasswordInput);
        newInput = findViewById(R.id.newPasswordInput);
        confirmInput = findViewById(R.id.confirmPasswordInput);
        errorText = findViewById(R.id.errorText);
        submitButton = findViewById(R.id.submitButton);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        submitButton.setOnClickListener(view -> submit());
    }

    private void submit() {
        String current = currentInput.getText().toString();
        String value = newInput.getText().toString();
        if (!value.equals(confirmInput.getText().toString())) {
            showError("两次输入的新密码不一致");
            return;
        }
        if (value.length() < 8 || !value.matches(".*[A-Za-z].*") || !value.matches(".*\\d.*")) {
            showError("新密码至少 8 位，并同时包含字母和数字");
            return;
        }
        submitButton.setEnabled(false);
        submitButton.setText("正在保存...");
        AppServices.auth(this).changePassword(current, value, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                Toast.makeText(ChangePasswordActivity.this, "密码已修改，请重新登录", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(ChangePasswordActivity.this, LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(String message) {
                submitButton.setEnabled(true);
                submitButton.setText("保存新密码");
                showError(message);
            }
        });
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }
}

