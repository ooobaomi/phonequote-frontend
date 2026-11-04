package com.local.phonequote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class LoginActivity extends Activity {
    private EditText accountInput;
    private EditText passwordInput;
    private Button loginButton;
    private ProgressBar loginProgress;
    private TextView loginError;
    private boolean submitting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        accountInput = findViewById(R.id.accountInput);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginButton);
        loginProgress = findViewById(R.id.loginProgress);
        loginError = findViewById(R.id.loginError);

        loginButton.setOnClickListener(view -> login());
        findViewById(R.id.registerButton).setOnClickListener(view ->
                startActivity(new Intent(this, RegisterActivity.class))
        );
        findViewById(R.id.forgotPasswordButton).setOnClickListener(view -> showSupport());
        AppServices.auth(this).getCurrentSession(new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession value) {
                openAfterLogin(value);
            }

            @Override
            public void onError(String message) {
                // 没有有效会话时停留在登录 Demo。
            }
        });
    }

    private void login() {
        if (submitting) {
            return;
        }
        submitting = true;
        loginError.setVisibility(View.GONE);
        loginButton.setEnabled(false);
        loginButton.setText("正在登录...");
        loginProgress.setVisibility(View.VISIBLE);

        AppServices.auth(this).login(
                accountInput.getText().toString(),
                passwordInput.getText().toString(),
                new ApiCallback<AccountSession>() {
                    @Override
                    public void onSuccess(AccountSession value) {
                        submitting = false;
                        openAfterLogin(value);
                    }

                    @Override
                    public void onError(String message) {
                        submitting = false;
                        loginButton.setEnabled(true);
                        loginButton.setText("登录");
                        loginProgress.setVisibility(View.GONE);
                        loginError.setText(message);
                        loginError.setVisibility(View.VISIBLE);
                    }
                }
        );
    }

    private void openAfterLogin(AccountSession session) {
        if (session.mustChangePassword) {
            startActivity(new Intent(this, ChangePasswordActivity.class)
                    .putExtra(ChangePasswordActivity.EXTRA_FORCED, true));
            return;
        }
        openMain();
    }

    private void showSupport() {
        AppServices.auth(this).getSupport(new ApiCallback<SupportInfo>() {
            @Override
            public void onSuccess(SupportInfo value) {
                StringBuilder message = new StringBuilder(value.message);
                if (!value.phone.isEmpty()) {
                    message.append("\n\n客服电话：").append(value.phone);
                }
                if (!value.wechat.isEmpty()) {
                    message.append("\n客服微信：").append(value.wechat);
                }
                new AlertDialog.Builder(LoginActivity.this)
                        .setTitle("人工客服找回密码")
                        .setMessage(message.toString())
                        .setPositiveButton("知道了", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                new AlertDialog.Builder(LoginActivity.this)
                        .setTitle("忘记密码")
                        .setMessage("请联系人工客服找回密码。\n" + message)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
