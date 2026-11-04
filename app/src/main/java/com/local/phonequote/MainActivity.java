package com.local.phonequote;

import android.Manifest;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_OVERLAY = 101;
    private static final int REQUEST_CAPTURE = 102;
    private static final int REQUEST_IMPORT = 103;
    private static final int REQUEST_NOTIFICATIONS = 104;

    private BottomNavigationView bottomNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_recognition) {
                showFragment(new RecognitionFragment(), "recognition");
            } else if (item.getItemId() == R.id.nav_upload) {
                showFragment(new UploadFragment(), "upload");
            } else if (item.getItemId() == R.id.nav_quotes) {
                showFragment(new QuoteTablesFragment(), "quotes");
            } else if (item.getItemId() == R.id.nav_account) {
                showFragment(new AccountFragment(), "account");
            } else {
                return false;
            }
            return true;
        });
        if (savedInstanceState == null) {
            bottomNavigation.setSelectedItemId(R.id.nav_recognition);
        }
        requestNotificationPermissionWhenNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppServices.auth(this).getCurrentSession(new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession session) {
                if (session.mustChangePassword) {
                    startActivity(new Intent(MainActivity.this, ChangePasswordActivity.class)
                            .putExtra(ChangePasswordActivity.EXTRA_FORCED, true));
                } else if (!session.profileCompleted) {
                    bottomNavigation.setSelectedItemId(R.id.nav_account);
                    Toast.makeText(MainActivity.this, "请先补全姓名", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String message) {
                stopAssistant();
                openLogin();
            }
        });
    }

    private void showFragment(Fragment fragment, String tag) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.mainFragmentContainer, fragment, tag)
                .commit();
    }

    public void openTab(int menuId) {
        bottomNavigation.setSelectedItemId(menuId);
    }

    public void startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQUEST_OVERLAY);
            notifyRecognitionState("等待授予悬浮窗权限");
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    public void stopAssistant() {
        if (CaptureService.isRunning()) {
            Intent intent = new Intent(this, CaptureService.class);
            intent.setAction(CaptureService.ACTION_STOP);
            startService(intent);
        }
        notifyRecognitionState("悬浮识别未启动");
    }

    public void chooseQuoteFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    public void openLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startAssistant();
            } else {
                notifyRecognitionState("需要悬浮窗权限才能显示识别按钮");
            }
            return;
        }
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, CaptureService.class);
                serviceIntent.setAction(CaptureService.ACTION_START);
                serviceIntent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                startForegroundService(serviceIntent);
                notifyRecognitionState("悬浮识别已启动，可切换到报价页面");
            } else {
                notifyRecognitionState("屏幕读取授权已取消");
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    uris.add(clipData.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importQuoteFiles(uris);
        }
    }

    private void importQuoteFiles(List<Uri> uris) {
        try {
            int count = 0;
            for (Uri uri : uris) {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("文件读取失败");
                    count += QuoteRepository.importFrom(this, input);
                }
            }
            Toast.makeText(this, "已按品牌导入 " + count + " 条报价", Toast.LENGTH_LONG).show();
            if (CaptureService.isRunning()) {
                startService(new Intent(this, CaptureService.class)
                        .setAction(CaptureService.ACTION_RELOAD_DATA));
            }
            notifyRecognitionRefresh();
        } catch (IOException | JSONException exception) {
            Toast.makeText(this, "导入失败：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void notifyRecognitionState(String state) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("recognition");
        if (fragment instanceof RecognitionFragment) {
            ((RecognitionFragment) fragment).showRunState(state);
        }
    }

    private void notifyRecognitionRefresh() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("recognition");
        if (fragment instanceof RecognitionFragment) {
            ((RecognitionFragment) fragment).refresh();
        }
    }

    private void requestNotificationPermissionWhenNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }
}
