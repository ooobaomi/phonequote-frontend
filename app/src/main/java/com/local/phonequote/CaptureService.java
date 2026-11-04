package com.local.phonequote;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CaptureService extends Service {
    public static final String ACTION_START = "com.local.phonequote.START";
    public static final String ACTION_STOP = "com.local.phonequote.STOP";
    public static final String ACTION_RELOAD_DATA = "com.local.phonequote.RELOAD_DATA";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 17;
    private static final long SESSION_CHECK_INTERVAL_MS = 60L * 1000L;
    private static final long OCR_INTERNAL_DEADLINE_MS = 6500L;
    private static final long OCR_HARD_DEADLINE_MS = 7000L;
    private static volatile boolean running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService matchExecutor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private View overlayView;
    private Button scanButton;
    private ImageButton collapseButton;
    private View resultPanel;
    private TextView resultTitle;
    private TextView resultMeta;
    private TextView rawText;
    private LinearLayout priceList;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer recognizer;
    private QuoteRepository repository;
    private boolean scanning;
    private boolean resultQueued;
    private long scanToken;
    private long scanStartedAt;
    private final List<List<OcrTextLine>> recognitionPasses = new ArrayList<>();
    private List<OcrTextLine> lastRecognitionLines = new ArrayList<>();
    private boolean shuttingDown;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        reloadRepository(false);
        createNotificationChannel();
        scheduleSessionCheck();
    }

    private void scheduleSessionCheck() {
        mainHandler.postDelayed(this::checkServerSession, SESSION_CHECK_INTERVAL_MS);
    }

    private void checkServerSession() {
        if (shuttingDown) {
            return;
        }
        AppServices.auth(this).getCurrentSession(new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession value) {
                scheduleSessionCheck();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(CaptureService.this, message, Toast.LENGTH_LONG).show();
                shutdown();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD_DATA.equals(action)) {
            reloadRepository(true);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startCaptureSession(intent);
        }
        return START_NOT_STICKY;
    }

    private void startCaptureSession(Intent intent) {
        startAsForeground();
        if (mediaProjection != null) {
            return;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            Toast.makeText(this, "屏幕读取授权无效", Toast.LENGTH_LONG).show();
            shutdown();
            return;
        }

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!shuttingDown) {
                    mainHandler.post(CaptureService.this::shutdown);
                }
            }
        }, mainHandler);

        createCaptureSurface();
        showOverlay();
        running = true;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createCaptureSurface() {
        releaseCaptureSurface();
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        imageReader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3
        );
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "phone-quote-capture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                mainHandler
        );
    }

    @SuppressLint("InflateParams")
    private void showOverlay() {
        if (overlayView != null) {
            return;
        }
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_quote, null);
        scanButton = overlayView.findViewById(R.id.scanButton);
        resultPanel = overlayView.findViewById(R.id.resultPanel);
        resultTitle = overlayView.findViewById(R.id.resultTitle);
        resultMeta = overlayView.findViewById(R.id.resultMeta);
        rawText = overlayView.findViewById(R.id.rawText);
        priceList = overlayView.findViewById(R.id.priceList);
        collapseButton = overlayView.findViewById(R.id.collapseButton);

        overlayParams = new WindowManager.LayoutParams(
                dp(56),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(10);
        overlayParams.y = dp(90);
        windowManager.addView(overlayView, overlayParams);

        attachScanButtonBehavior();
        collapseButton.setOnClickListener(view -> setOverlayExpanded(false));
        attachDragBehavior(overlayView.findViewById(R.id.dragBar));
    }

    private void attachScanButtonBehavior() {
        scanButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = overlayParams.x;
                        initialY = overlayParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true;
                        if (moved) {
                            overlayParams.x = initialX - Math.round(dx);
                            overlayParams.y = initialY + Math.round(dy);
                            windowManager.updateViewLayout(overlayView, overlayParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) captureCurrentScreen();
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void setOverlayExpanded(boolean expanded) {
        if (overlayView == null || overlayParams == null) return;
        resultPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        collapseButton.setVisibility(expanded ? View.VISIBLE : View.GONE);
        overlayParams.width = dp(expanded ? 220 : 56);
        windowManager.updateViewLayout(overlayView, overlayParams);
        if (!scanning) scanButton.setText(expanded ? "重识别" : "识别");
    }

    private void attachDragBehavior(View dragView) {
        dragView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = overlayParams.x;
                        initialY = overlayParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        overlayParams.x = initialX - Math.round(event.getRawX() - initialTouchX);
                        overlayParams.y = initialY + Math.round(event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, overlayParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void captureCurrentScreen() {
        if (scanning) {
            return;
        }
        if (repository == null) {
            finishScanWithError("报价库读取失败");
            return;
        }
        if (imageReader == null) {
            finishScanWithError("截图服务未就绪");
            return;
        }
        scanning = true;
        resultQueued = false;
        scanToken++;
        long token = scanToken;
        scanStartedAt = SystemClock.elapsedRealtime();
        recognitionPasses.clear();
        lastRecognitionLines = new ArrayList<>();
        scanButton.setEnabled(false);
        scanButton.setText("识别中");
        setOverlayExpanded(false);
        discardQueuedImages();
        overlayView.setVisibility(View.INVISIBLE);
        mainHandler.postDelayed(() -> acquireScreenshot(0, token), 220L);
        mainHandler.postDelayed(() -> {
            if (scanning && token == scanToken && !resultQueued) {
                if (recognitionPasses.isEmpty()) finishScanWithError("识别超时，请重新识别");
                else queueFinalMatch(token);
            }
        }, OCR_INTERNAL_DEADLINE_MS);
        mainHandler.postDelayed(() -> {
            if (scanning && token == scanToken) {
                finishScanWithError("识别已达到7秒，请重新识别");
            }
        }, OCR_HARD_DEADLINE_MS);
    }

    private void acquireScreenshot(int attempt, long token) {
        if (token != scanToken || !scanning) return;
        if (imageReader == null || shuttingDown) {
            finishScanWithError("截图服务已停止");
            return;
        }
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            if (attempt < 8) {
                mainHandler.postDelayed(() -> acquireScreenshot(attempt + 1, token), 80L);
            } else {
                restoreOverlay();
                finishScanWithError("暂时没有读取到屏幕，请重试");
            }
            return;
        }

        Bitmap bitmap;
        try {
            bitmap = imageToBitmap(image);
        } finally {
            image.close();
        }
        restoreOverlay();
        processPrimaryPass(token, bitmap);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap padded = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != padded) {
            padded.recycle();
        }
        return cropped;
    }

    private void processPrimaryPass(long token, Bitmap bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(task -> {
            if (token != scanToken || !scanning || resultQueued) {
                bitmap.recycle();
                return;
            }
            if (!task.isSuccessful() || task.getResult() == null) {
                bitmap.recycle();
                finishScanWithError("文字识别失败，请重试");
                return;
            }
            List<OcrTextLine> lines = toLines(task.getResult(), 0);
            recognitionPasses.add(lines);
            Bitmap crop = createModelCrop(bitmap, lines);
            bitmap.recycle();
            if (crop == null || remainingOcrTime() < 850L) {
                if (crop != null) crop.recycle();
                queueFinalMatch(token);
                return;
            }
            processLocalPass(token, crop, 1);
        });
    }

    private void processLocalPass(long token, Bitmap bitmap, int passIndex) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(task -> {
            if (token != scanToken || !scanning || resultQueued) {
                bitmap.recycle();
                return;
            }
            if (task.isSuccessful() && task.getResult() != null) {
                recognitionPasses.add(toLines(task.getResult(), passIndex));
            }
            MatchResult preliminary = OcrParser.matchPasses(
                    recognitionPasses,
                    repository == null ? new ArrayList<>() : repository.getItems(),
                    elapsedOcrTime()
            );
            if (passIndex == 1
                    && preliminary.status == MatchResult.Status.MODEL_CONFIRMATION_REQUIRED
                    && remainingOcrTime() >= 1000L) {
                Bitmap contrast = highContrast(bitmap);
                bitmap.recycle();
                processLocalPass(token, contrast, 2);
                return;
            }
            bitmap.recycle();
            queueFinalMatch(token);
        });
    }

    private List<OcrTextLine> toLines(Text text, int passIndex) {
        List<OcrTextLine> result = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) {
                    result.add(new OcrTextLine(line.getText(), 0, result.size() * 100,
                            0, result.size() * 100 + 60, passIndex));
                } else {
                    result.add(new OcrTextLine(line.getText(), box.left, box.top,
                            box.right, box.bottom, passIndex));
                }
            }
        }
        return result;
    }

    private Bitmap createModelCrop(Bitmap source, List<OcrTextLine> lines) {
        QuoteRepository current = repository;
        if (current == null) return null;
        List<OcrTextLine> likely = OcrParser.findLikelyModelLines(lines, current.getItems());
        OcrTextLine target = likely.isEmpty() ? fallbackDigitLine(lines) : likely.get(0);
        if (target == null || target.width() <= 0 || target.height() <= 0) return null;
        int horizontalPadding = Math.max(dp(24), source.getWidth() / 8);
        int verticalPadding = Math.max(dp(28), target.height() * 2);
        int left = Math.max(0, target.left - horizontalPadding);
        int top = Math.max(0, target.top - verticalPadding);
        int right = Math.min(source.getWidth(), target.right + horizontalPadding);
        int bottom = Math.min(source.getHeight(), target.bottom + verticalPadding);
        if (right <= left || bottom <= top) return null;
        Bitmap crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
        int scaledWidth = Math.min(2048, crop.getWidth() * 2);
        int scaledHeight = Math.max(1, Math.round(crop.getHeight() * (scaledWidth / (float) crop.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(crop, scaledWidth, scaledHeight, true);
        if (scaled != crop) crop.recycle();
        return scaled;
    }

    private OcrTextLine fallbackDigitLine(List<OcrTextLine> lines) {
        for (OcrTextLine line : lines) {
            if (line.text.matches(".*[0-9].*") && line.text.length() <= 80 && line.width() > 0) return line;
        }
        return null;
    }

    private Bitmap highContrast(Bitmap source) {
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        ColorMatrix contrast = new ColorMatrix(new float[] {
                1.55f, 0, 0, 0, -65,
                0, 1.55f, 0, 0, -65,
                0, 0, 1.55f, 0, -65,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(contrast);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(source, 0, 0, paint);
        return output;
    }

    private long elapsedOcrTime() {
        return Math.max(0L, SystemClock.elapsedRealtime() - scanStartedAt);
    }

    private long remainingOcrTime() {
        return Math.max(0L, OCR_INTERNAL_DEADLINE_MS - elapsedOcrTime());
    }

    private void queueFinalMatch(long token) {
        if (resultQueued || token != scanToken || !scanning) return;
        resultQueued = true;
        QuoteRepository current = repository;
        List<List<OcrTextLine>> passes = new ArrayList<>();
        for (List<OcrTextLine> pass : recognitionPasses) passes.add(new ArrayList<>(pass));
        matchExecutor.execute(() -> {
            MatchResult match = current == null
                    ? OcrParser.matchLines(new ArrayList<>(), new ArrayList<>())
                    : OcrParser.matchPasses(passes, current.getItems(), elapsedOcrTime());
            List<OcrTextLine> combined = new ArrayList<>();
            for (List<OcrTextLine> pass : passes) combined.addAll(pass);
            mainHandler.post(() -> {
                if (token != scanToken || !scanning) return;
                lastRecognitionLines = combined;
                showMatchResult(match);
            });
        });
    }

    private void showMatchResult(MatchResult match) {
        if (shuttingDown || overlayView == null) {
            return;
        }
        priceList.removeAllViews();
        rawText.setVisibility(match.recognizedSummary.isEmpty() ? View.GONE : View.VISIBLE);
        rawText.setText(match.recognizedSummary);

        switch (match.status) {
            case MATCHED:
                showMatchedQuote(match);
                break;
            case MODEL_CONFIRMATION_REQUIRED:
                showCandidateConfirmation(match);
                break;
            case STORAGE_NOT_FOUND:
                resultTitle.setText(match.matchedModel);
                resultMeta.setText("已识别型号，未识别到容量");
                break;
            case PRICE_NOT_FOUND:
                resultTitle.setText(match.matchedModel);
                resultMeta.setText("识别到容量 " + formatCapacities(match) + "，本地报价库暂无对应配置");
                break;
            case BRAND_NOT_FOUND:
                resultTitle.setText("未识别到手机品牌");
                resultMeta.setText("请让品牌、型号和内存配置同时显示后重试");
                break;
            case MODEL_NOT_FOUND:
            default:
                resultTitle.setText("未匹配到手机型号");
                resultMeta.setText("请确认型号和容量完整显示后重试");
                break;
        }
        setOverlayExpanded(true);
        finishScan();
    }

    private void showCandidateConfirmation(MatchResult match) {
        resultTitle.setText("请确认手机型号");
        resultMeta.setText("关键数字或系列识别结果不一致，确认后再显示价格");
        for (ModelCandidate candidate : match.candidates) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(candidate.brand + " " + candidate.model);
            button.setTextSize(12f);
            button.setTextColor(getColor(R.color.blue_text));
            button.setBackgroundResource(R.drawable.bg_button_secondary);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            );
            params.topMargin = dp(4);
            button.setLayoutParams(params);
            button.setOnClickListener(view -> confirmCandidate(candidate));
            priceList.addView(button);
        }
    }

    private void confirmCandidate(ModelCandidate candidate) {
        QuoteRepository current = repository;
        if (current == null) {
            finishScanWithError("报价库读取失败");
            return;
        }
        scanButton.setEnabled(false);
        matchExecutor.execute(() -> {
            MatchResult confirmed = OcrParser.matchModel(
                    new ArrayList<>(lastRecognitionLines), candidate.model, current.getItems()
            );
            mainHandler.post(() -> {
                scanButton.setEnabled(true);
                showMatchResult(confirmed);
            });
        });
    }

    private void showMatchedQuote(MatchResult match) {
        QuoteItem item = match.item;
        String memory = item.configuration.isEmpty()
                ? item.storage
                : formatConfiguration(item.configuration);
        resultTitle.setText(item.model + "  " + memory);
        resultMeta.setText(
                item.brand + " · 匹配度 " + Math.round(match.confidence * 100d) + "%"
        );
        NumberFormat currency = NumberFormat.getIntegerInstance(Locale.CHINA);
        for (QuoteItem.GradePrice grade : item.grades) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, 0);

            TextView name = new TextView(this);
            name.setText(grade.name);
            name.setTextColor(getColor(R.color.muted));
            name.setTextSize(12f);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(24), 1f));

            TextView price = new TextView(this);
            price.setText("¥" + currency.format(grade.price));
            price.setTextColor(getColor(R.color.green_dark));
            price.setTextSize(15f);
            price.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(price, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(24)
            ));
            priceList.addView(row);
        }
    }

    private String formatCapacities(MatchResult result) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < result.detectedCapacitiesGb.size(); i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            int gb = result.detectedCapacitiesGb.get(i);
            builder.append(gb >= 1024 && gb % 1024 == 0 ? (gb / 1024) + "TB" : gb + "GB");
        }
        return builder.toString();
    }

    private String formatConfiguration(String configuration) {
        String[] parts = configuration.toUpperCase(Locale.ROOT).split("\\+");
        if (parts.length != 2) {
            return configuration;
        }
        String storage = parts[1].endsWith("TB") ? parts[1] : parts[1] + "GB";
        return parts[0] + "GB+" + storage;
    }

    private void finishScanWithError(String message) {
        restoreOverlay();
        resultTitle.setText(message);
        resultMeta.setText("");
        rawText.setText("");
        priceList.removeAllViews();
        setOverlayExpanded(true);
        finishScan();
    }

    private void finishScan() {
        scanning = false;
        resultQueued = false;
        scanButton.setEnabled(true);
        scanButton.setText(resultPanel.getVisibility() == View.VISIBLE ? "重识别" : "识别");
    }

    private void restoreOverlay() {
        if (overlayView != null) {
            overlayView.setVisibility(View.VISIBLE);
        }
    }

    private void discardQueuedImages() {
        if (imageReader == null) {
            return;
        }
        Image image;
        while ((image = imageReader.acquireLatestImage()) != null) {
            image.close();
        }
    }

    private void reloadRepository(boolean notify) {
        try {
            repository = new QuoteRepository(this);
            if (notify) {
                Toast.makeText(this, "报价库已刷新", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException | JSONException exception) {
            repository = null;
            Toast.makeText(this, "报价库读取失败：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mediaProjection != null && virtualDisplay != null) {
            resizeCaptureSurface();
        }
    }

    private void resizeCaptureSurface() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        ImageReader replacement = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3
        );
        ImageReader previous = imageReader;
        virtualDisplay.setSurface(replacement.getSurface());
        virtualDisplay.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        imageReader = replacement;
        if (previous != null) {
            previous.close();
        }
    }

    private void releaseCaptureSurface() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        releaseCaptureSurface();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        matchExecutor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (!shuttingDown) {
            shutdown();
        }
        recognizer.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "悬浮识别",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持手动截屏识别服务运行");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("回收报价助手正在运行")
                .setContentText("滑到目标页面后点击悬浮按钮")
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "停止",
                        stopPendingIntent
                ).build())
                .build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
