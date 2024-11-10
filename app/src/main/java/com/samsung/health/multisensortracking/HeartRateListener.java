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
        // 심박수 데이터 가져오기
        int heartRate = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE);

        // 현재 날짜 및 시간 형식으로 타임스탬프 생성 (밀리초 포함)
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date());

        // 심박수 목록에 추가 (HRV 계산을 위한 데이터 유지)
        heartRateList.add(heartRate);

        // HRV 계산 (기본적으로 최근 N개의 심박수를 이용한 표준편차 계산)
        double hrv = calculateHRV();

        // 데이터 구조 생성
        Map<String, Object> heartRateData = new HashMap<>();
        heartRateData.put("timestamp", timestamp);
        heartRateData.put("heartRate", heartRate);
        heartRateData.put("hrv", hrv);

        // Firebase에 데이터 저장
        databaseReference.push().setValue(heartRateData)
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Heart Rate and HRV data uploaded successfully"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to upload Heart Rate and HRV data", e));

        Log.d(APP_TAG, "Heart Rate: " + heartRate + " BPM, HRV: " + hrv + ", Timestamp: " + timestamp);
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
}
