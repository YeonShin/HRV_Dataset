package com.samsung.health.multisensortracking;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;

public class HeartRateListener extends BaseListener {
    private static final String APP_TAG = "HeartRateListener";
    private DatabaseReference databaseReference;  // Firebase Database Reference
    private List<Integer> heartRateList = new ArrayList<>();  // 심박수 목록 (HRV 계산을 위해)
    private List<String> timestampList = new ArrayList<>(); // 타임스탬프 목록 (첫 번째 데이터의 타임스탬프를 기록)
    private boolean shouldUploadData = false; // 데이터 업로드 여부
    private int dataIndex = 0;  // 데이터를 순차적으로 저장할 인덱스
    private int errorCount = 0;  // 잘못된 심박수 데이터 카운트

    HeartRateListener() {
        // Firebase Database 초기화
        databaseReference = FirebaseDatabase.getInstance().getReference("HeartRateData");

        // Firebase 데이터 전체 삭제
        clearExistingData();

        // HealthTracker 이벤트 리스너
        final HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> list) {
                for (DataPoint data : list) {
                    updateHeartRate(data);
                }
            }

            @Override
            public void onFlushCompleted() {
                Log.i(APP_TAG, "onFlushCompleted called");
            }

            @Override
            public void onError(HealthTracker.TrackerError trackerError) {
                Log.e(APP_TAG, "onError called: " + trackerError);
                setHandlerRunning(false);
            }
        };
        setTrackerEventListener(trackerEventListener);
    }

    private void clearExistingData() {
        databaseReference.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "All existing Heart Rate data cleared"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear existing Heart Rate data", e));
    }

    public void updateHeartRate(DataPoint dataPoint) {
        if (!shouldUploadData) return; // 업로드가 활성화되지 않으면 리턴

        int heartRate = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

        // 심박수가 0이면 에러로 간주
        if (heartRate == 0) {
            errorCount++;
        }

        heartRateList.add(heartRate);
        timestampList.add(timestamp);

        // 120개의 데이터가 모이면 업로드
        if (heartRateList.size() >= 120) {
            double hrv = calculateHRV();
            uploadHeartRateData(hrv, timestamp);
            resetData();  // 데이터 초기화
        }
    }

    // HRV 계산: 최근 N개의 심박수 간격의 표준편차 계산
    private double calculateHRV() {
        if (heartRateList.size() < 2) {
            return 0.0;  // 최소 2개 이상의 데이터가 있어야 HRV를 계산할 수 있음
        }

        // 심박수 간격 (R-R 간격)을 구해서 HRV 계산
        List<Integer> rrIntervals = new ArrayList<>();
        for (int i = 1; i < heartRateList.size(); i++) {
            int rrInterval = Math.abs(heartRateList.get(i) - heartRateList.get(i - 1));  // 심박수 간 간격
            rrIntervals.add(rrInterval);
        }

        // 표준편차 계산
        return calculateStandardDeviation(rrIntervals);
    }

    // 표준편차 계산
    private double calculateStandardDeviation(List<Integer> intervals) {
        double mean = 0.0;
        for (int interval : intervals) {
            mean += interval;
        }
        mean /= intervals.size();

        double variance = 0.0;
        for (int interval : intervals) {
            variance += Math.pow(interval - mean, 2);
        }
        variance /= intervals.size();

        return Math.sqrt(variance);
    }

    // 데이터를 Firebase에 업로드
    private void uploadHeartRateData(double hrv, String timestamp) {
        Map<String, Object> heartRateData = new HashMap<>();
        heartRateData.put("errorCount", errorCount);
        heartRateData.put("hrv", hrv);
        heartRateData.put("timestamp", timestamp);

        String index = String.valueOf(getNextIndex());

        databaseReference.child(index).setValue(heartRateData)
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Heart Rate and HRV data uploaded successfully"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to upload Heart Rate and HRV data", e));

        Log.d(APP_TAG, "Data uploaded. HRV: " + hrv + ", ErrorCount: " + errorCount + ", Timestamp: " + timestamp);
    }

    // 순차적인 인덱스를 반환
    private int getNextIndex() {
        return dataIndex++;  // 순차적으로 0, 1, 2...가 반환됨
    }

    // 데이터를 초기화
    private void resetData() {
        heartRateList.clear();
        timestampList.clear();
        errorCount = 0;
    }

    public void startDataUpload() {
        shouldUploadData = true;
    }

    public void stopDataUpload() {
        shouldUploadData = false;
    }
}
