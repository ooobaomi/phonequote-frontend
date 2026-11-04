package com.local.phonequote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class RegisterActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText phoneInput;
    private EditText nameInput;
    private EditText codeInput;
    private EditText passwordInput;
    private EditText confirmPasswordInput;
    private Button sendCodeButton;
    private Button registerButton;
    private TextView errorText;
    private ProgressBar progress;
    private int countdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        phoneInput = findViewById(R.id.phoneInput);
        nameInput = findViewById(R.id.nameInput);
        codeInput = findViewById(R.id.codeInput);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        sendCodeButton = findViewById(R.id.sendCodeButton);
        registerButton = findViewById(R.id.registerButton);
        errorText = findViewById(R.id.errorText);
        progress = findViewById(R.id.progress);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        sendCodeButton.setOnClickListener(view -> sendCode());
        registerButton.setOnClickListener(view -> register());
    }

    private void sendCode() {
        String phone = phoneInput.getText().toString().trim();
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            showError("请输入正确的 11 位手机号");
            return;
        }
        errorText.setVisibility(View.GONE);
        sendCodeButton.setEnabled(false);
        AppServices.auth(this).sendRegisterCode(phone, new ApiCallback<Integer>() {
            @Override
            public void onSuccess(Integer seconds) {
                countdown = seconds;
                tickCountdown();
            }

            @Override
            public void onError(String message) {
                sendCodeButton.setEnabled(true);
                showError(message);
            }
        });
    }

    private void tickCountdown() {
        if (countdown <= 0) {
            sendCodeButton.setEnabled(true);
            sendCodeButton.setText("重新获取");
            return;
        }
        sendCodeButton.setText(countdown + " 秒");
        countdown--;
        handler.postDelayed(this::tickCountdown, 1000L);
    }

    private void register() {
        String name = nameInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (name.isEmpty()) {
            showError("请填写姓名");
            return;
        }
        if (!phone.matches("^1[3-9]\\d{9}$") || code.length() < 4) {
            showError("请填写手机号和短信验证码");
            return;
        }
        if (!password.equals(confirmPasswordInput.getText().toString())) {
            showError("两次输入的密码不一致");
            return;
        }
        if (password.length() < 8 || !password.matches(".*[A-Za-z].*")
                || !password.matches(".*\\d.*")) {
            showError("密码至少 8 位，并同时包含字母和数字");
            return;
        }
        setSubmitting(true);
        AppServices.auth(this).register(name, phone, password, code, new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession value) {
                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(String message) {
                setSubmitting(false);
                showError(message);
            }
        });
    }

    private void setSubmitting(boolean value) {
        registerButton.setEnabled(!value);
        registerButton.setText(value ? "正在注册..." : "验证并注册");
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
