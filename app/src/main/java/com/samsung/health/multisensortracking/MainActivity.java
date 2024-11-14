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
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.health.multisensortracking.databinding.ActivityMainBinding;

import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private final static String APP_TAG = "MainActivity";
    private final static int MEASUREMENT_DURATION = 3600000; //측정 길이
    private final static Long MEASUREMENT_TICK = 250L; // 측정 간격, ms단위

    private final AtomicBoolean isMeasurementRunning = new AtomicBoolean(false);
    Thread uiUpdateThread = null;
    private ConnectionManager connectionManager;
    private HeartRateListener heartRateListener = null;
    private boolean connected = false;
    private boolean permissionGranted = false;
    private HeartRateData heartRateDataLast = new HeartRateData();
    private TextView txtHeartRate;
    private TextView txtStatus;
    private Button butStart;
    private CircularProgressIndicator measurementProgress = null;
    final CountDownTimer countDownTimer = new CountDownTimer(MEASUREMENT_DURATION, MEASUREMENT_TICK) {
        @Override
        public void onTick(long timeLeft) {
            if (isMeasurementRunning.get()) {
                runOnUiThread(() ->
                        measurementProgress.setProgress(measurementProgress.getProgress() + 1, true));
            } else
                cancel();
        }

        @Override
        public void onFinish() {
            if (!isMeasurementRunning.get())
                return;
            Log.i(APP_TAG, "Failed measurement");
            heartRateListener.stopTracker();
            isMeasurementRunning.set(false);
            runOnUiThread(() -> {
                txtStatus.setText(R.string.MeasurementFailed);
                txtStatus.invalidate();
                txtHeartRate.setText(R.string.HeartRateDefaultValue);
                txtHeartRate.invalidate();
                butStart.setText(R.string.StartLabel);
                measurementProgress.setProgress(measurementProgress.getMax(), true);
                measurementProgress.invalidate();
            });
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };
    final TrackerDataObserver trackerDataObserver = new TrackerDataObserver() {
        @Override
        public void onHeartRateTrackerDataChanged(HeartRateData hrData) {
            MainActivity.this.runOnUiThread(() -> {
                heartRateDataLast = hrData;
                Log.i(APP_TAG, "HR Status: " + hrData.status);
                if (hrData.status == HeartRateStatus.HR_STATUS_FIND_HR) {
                    txtHeartRate.setText(String.valueOf(hrData.hr));
                    Log.i(APP_TAG, "HR: " + hrData.hr);
                } else {
                    txtHeartRate.setText(getString(R.string.HeartRateDefaultValue));
                }
            });
        }

        // SpO2 관련 메서드 빈 구현
        @Override
        public void onSpO2TrackerDataChanged(int status, int spO2Value) {
            // SpO2와 관련된 로직 제거, 빈 구현
        }

        @Override
        public void onError(int errorResourceId) {
            runOnUiThread(() ->
                    Toast.makeText(getApplicationContext(), getString(errorResourceId), Toast.LENGTH_LONG).show());
            countDownTimer.onFinish();
        }
    };

    private final ConnectionObserver connectionObserver = new ConnectionObserver() {
        @Override
        public void onConnectionResult(int stringResourceId) {
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), getString(stringResourceId), Toast.LENGTH_LONG).show());

            if (stringResourceId != R.string.ConnectedToHs) {
                finish();
            }

            connected = true;
            TrackerDataNotifier.getInstance().addObserver(trackerDataObserver);

            heartRateListener = new HeartRateListener();
            connectionManager.initHeartRate(heartRateListener);
            // heartRateListener.startTracker(); // 여기서 제거
        }

        @Override
        public void onError(HealthTrackerException e) {
            if (e.getErrorCode() == HealthTrackerException.OLD_PLATFORM_VERSION || e.getErrorCode() == HealthTrackerException.PACKAGE_NOT_INSTALLED)
                runOnUiThread(() -> Toast.makeText(getApplicationContext()
                        , getString(R.string.HealthPlatformVersionIsOutdated), Toast.LENGTH_LONG).show());
            if (e.hasResolution()) {
                e.resolve(MainActivity.this);
            } else {
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), getString(R.string.ConnectionError)
                        , Toast.LENGTH_LONG).show());
                Log.e(APP_TAG, "Could not connect to Health Tracking Service: " + e.getMessage());
            }
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

//        txtHeartRate = binding.txtHeartRate;
//        txtStatus = binding.txtStatus;
        butStart = binding.butStart;
        measurementProgress = binding.progressBar;
        adjustProgressBar(measurementProgress);
        measurementProgress.setMax((int) (MEASUREMENT_DURATION / MEASUREMENT_TICK));
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), getString(R.string.BodySensors)) == PackageManager.PERMISSION_DENIED)
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
        TrackerDataNotifier.getInstance().removeObserver(trackerDataObserver);
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

    public void onDetails(View view) {
        if (isPermissionsOrConnectionInvalid()) {
            return;
        }

        final Intent intent = new Intent(this, DetailsActivity.class);
        intent.putExtra(getString(R.string.ExtraHr), heartRateDataLast.hr);
        intent.putExtra(getString(R.string.ExtraHrStatus), heartRateDataLast.status);
        intent.putExtra(getString(R.string.ExtraIbi), heartRateDataLast.ibi);
        intent.putExtra(getString(R.string.ExtraQualityIbi), heartRateDataLast.qIbi);
        startActivity(intent);
    }

    public void performMeasurement(View view) {
        if (isPermissionsOrConnectionInvalid()) {
            return;
        }

        if (!isMeasurementRunning.get()) {
            butStart.setText(R.string.StopLabel);
            measurementProgress.setProgress(0);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            heartRateListener.startTracker(); // 측정 시작
            heartRateListener.startDataUpload(); // 데이터 업로드 활성화
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
                txtStatus.setText(R.string.StatusDefaultValue);
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