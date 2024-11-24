package com.samsung.health.multisensortracking;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.health.multisensortracking.databinding.ActivityMainBinding;

import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private final static String APP_TAG = "MainActivity";
    private final static int MEASUREMENT_DURATION = 3600000; // 측정 길이(1시간)
    private final static Long MEASUREMENT_TICK = 250L; // 측정 간격, ms단위

    private final AtomicBoolean isMeasurementRunning = new AtomicBoolean(false); // 측정 실행 여부 플래그
    Thread uiUpdateThread = null;
    private ConnectionManager connectionManager; // HealthTrackingService와 연결 관리
    private HeartRateListener heartRateListener = null;// 심박수 리스너
    private boolean connected = false; // 서비스 연결 여부
    private boolean permissionGranted = false; // 권한 부여 여부

    private TextView txtHeartRate; // 심박수 표시 텍스트뷰
    private TextView txtTimeElapsed; // 경과 시간 표시 텍스트뷰
    private Button butStart; // 시작/중지 버튼
    private CircularProgressIndicator measurementProgress = null; // 측정 진행 표시기
    private DatabaseReference databaseReference;


    // 측정 타이머
    final CountDownTimer countDownTimer = new CountDownTimer(MEASUREMENT_DURATION, MEASUREMENT_TICK) {
        @Override
        public void onTick(long timeLeft) {
            if (isMeasurementRunning.get()) {
                // 측정 진행 UI 업데이트
                runOnUiThread(() ->
                        measurementProgress.setProgress(measurementProgress.getProgress() + 1, true));

                // 경과 시간 계산
                long elapsedMillis = MEASUREMENT_DURATION - timeLeft;
                long seconds = (elapsedMillis / 1000) % 60;
                long minutes = (elapsedMillis / (1000 * 60)) % 60;
                long hours = (elapsedMillis / (1000 * 60 * 60));

                String elapsedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

                // txtTimeElapsed에 경과 시간 설정
                txtTimeElapsed.setText(elapsedTime);
            } else
                cancel();
        }

        @Override
        public void onFinish() {
            if (!isMeasurementRunning.get())
                return;

            // 측정 종료 처리
            heartRateListener.stopTracker();
            isMeasurementRunning.set(false);
            runOnUiThread(() -> {
                txtHeartRate.setText(R.string.HeartRateDefaultValue);
                txtTimeElapsed.setText("00:00:00"); // 초기화
                butStart.setText(R.string.StartLabel);
                measurementProgress.setProgress(measurementProgress.getMax(), true);
            });
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    // 연결 상태 옵저버
    private final ConnectionObserver connectionObserver = new ConnectionObserver() {
        @Override
        public void onConnectionResult(int stringResourceId) {
            runOnUiThread(() ->
                    Toast.makeText(getApplicationContext(), getString(stringResourceId), Toast.LENGTH_LONG).show());

            if (stringResourceId != R.string.ConnectedToHs) {
                finish(); // 연결 실패 시 종료
            }

            connected = true; // 연결 성공
            heartRateListener = new HeartRateListener();// 리스너 초기화
            connectionManager.initHeartRate(heartRateListener); // 심박수 초기화
        }

        @Override
        public void onError(HealthTrackerException e) {
            if (e.hasResolution()) {
                e.resolve(MainActivity.this); // 문제 해결
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.ConnectionError), Toast.LENGTH_LONG).show();
                Log.e(APP_TAG, "Connection error: " + e.getMessage());
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        databaseReference = FirebaseDatabase.getInstance().getReference("HeartRateData");

        // View 초기화
        final ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        txtHeartRate = binding.txtHeartRate;
        txtTimeElapsed = binding.txtTimeElapsed; // txtTimeElapsed 바인딩
        butStart = binding.butStart;
        measurementProgress = binding.progressBar;

        adjustProgressBar(measurementProgress);
        measurementProgress.setMax((int) (MEASUREMENT_DURATION / MEASUREMENT_TICK));


        if (ActivityCompat.checkSelfPermission(getApplicationContext(), getString(R.string.BodySensors))
                == PackageManager.PERMISSION_DENIED)
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 0);
        else {
            permissionGranted = true;
            createConnectionManager();
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (heartRateListener != null)
            heartRateListener.stopTracker();
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
    }

    void createConnectionManager() {
        try {
            connectionManager = new ConnectionManager(connectionObserver);
            connectionManager.connect(getApplicationContext());

        } catch (Throwable t) {
            Log.e(APP_TAG, t.getMessage());
        }
    }

    void adjustProgressBar(CircularProgressIndicator progressBar) {
        final DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        final int pxWidth = displayMetrics.widthPixels;
        final int padding = 1;
        progressBar.setPadding(padding, padding, padding, padding);
        final int trackThickness = progressBar.getTrackThickness();

        final int progressBarSize = pxWidth - trackThickness - 2 * padding;
        progressBar.setIndicatorSize(progressBarSize);
    }



    public void performMeasurement(View view) {
        if (isPermissionsOrConnectionInvalid()) {
            return;
        }

        if (!isMeasurementRunning.get()) {
            butStart.setText(R.string.StopLabel);
            measurementProgress.setProgress(0);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            // HeartRateListener에 업데이트 리스너 설정
            heartRateListener.setHeartRateUpdateListener(heartRate -> runOnUiThread(() -> {
                String heartRateText = heartRate + " bpm";
                txtHeartRate.setText(heartRateText);
            }));

            heartRateListener.startTracker(); // 측정 시작
            heartRateListener.startDataUpload(); // 데이터 업로드 활성화
            databaseReference.removeValue()
                    .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Firebase data cleared successfully"))
                    .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear Firebase data", e));

            isMeasurementRunning.set(true);
            uiUpdateThread = new Thread(countDownTimer::start);
            uiUpdateThread.start();
        } else {
            butStart.setEnabled(false);
            isMeasurementRunning.set(false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            heartRateListener.stopTracker(); // 측정 종료
            heartRateListener.stopDataUpload(); // 데이터 업로드 비활성화

            final Handler progressHandler = new Handler(Looper.getMainLooper());
            progressHandler.postDelayed(() -> {
                butStart.setText(R.string.StartLabel);
                measurementProgress.setProgress(0);
                butStart.setEnabled(true);
            }, MEASUREMENT_TICK * 2);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 0) {
            permissionGranted = true;
            for (int i = 0; i < permissions.length; ++i) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    //User denied permissions twice - permanent denial:
                    if (!shouldShowRequestPermissionRationale(permissions[i]))
                        Toast.makeText(getApplicationContext(), getString(R.string.PermissionDeniedPermanently), Toast.LENGTH_LONG).show();
                        //User denied permissions once:
                    else
                        Toast.makeText(getApplicationContext(), getString(R.string.PermissionDeniedRationale), Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            createConnectionManager();
        }
    }

    private boolean isPermissionsOrConnectionInvalid() {
        if (!permissionGranted) {
            Toast.makeText(getApplicationContext(), getString(R.string.PermissionDenied), Toast.LENGTH_SHORT).show();
            return true;
        }

        if (!connected) {
            Toast.makeText(getApplicationContext(), getString(R.string.NotConnected), Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }
}