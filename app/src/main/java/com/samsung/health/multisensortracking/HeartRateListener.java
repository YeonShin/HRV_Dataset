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
    private int measureCount = 0;
    private int errorCount = 0;  // 잘못된 심박수 데이터 카운트
    private List<Double> rrIntervalList = new ArrayList<>();  // R-R 간격 목록

    private HeartRateUpdateListener updateListener;

    public void setHeartRateUpdateListener(HeartRateUpdateListener listener) {
        this.updateListener = listener;
    }

    public interface HeartRateUpdateListener {
        void onHeartRateUpdate(int heartRate);
    }


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

        if (updateListener != null) {
            updateListener.onHeartRateUpdate(heartRate); // MainActivity에 업데이트 전달
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

        // R-R 간격 계산
        double rrInterval = 60000.0 / heartRate;

        // 심박수가 0이면 에러로 간주
        if (heartRate <= 40 || heartRate >= 180 || rrInterval < 300 || rrInterval > 2000) {
            errorCount++;
            measureCount++;
            return;
        }



        // 데이터 저장
        heartRateList.add(heartRate);
        rrIntervalList.add(rrInterval);
        timestampList.add(timestamp);
        measureCount++;

        // 데이터 업로드
        if (measureCount >= 120) { // 10개의 데이터가 모이면 업로드
            double hrv = calculateHRV();
            uploadHeartRateData(hrv, heartRate, rrInterval, timestamp);
            resetData(); // 데이터 초기화
        }
    }


    // HRV 계산: 최근 N개의 심박수 간격의 표준편차 계산
    private double calculateHRV() {
        if (rrIntervalList.size() < 2) {
            return 0.0;  // 유효한 데이터가 부족하면 0 반환
        }

        // 평균 계산
        double mean = rrIntervalList.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        // 분산 계산 및 표준편차 반환
        double variance = rrIntervalList.stream()
                .mapToDouble(interval -> Math.pow(interval - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance); // SDNN 값 반환

        // RMSSD 방식
//        if (rrIntervalList.size() < 2) {
//            return 0.0;  // 유효한 RR 간격이 2개 이상이어야 RMSSD 계산 가능
//        }
//
//        double sumSquaredDifferences = 0.0;
//
//        // 연속된 R-R 간격 차이의 제곱합 계산
//        for (int i = 1; i < rrIntervalList.size(); i++) {
//            double diff = rrIntervalList.get(i) - rrIntervalList.get(i - 1);
//            sumSquaredDifferences += Math.pow(diff, 2);
//        }
//
//        // 평균의 제곱근 반환
//        double meanSquaredDifferences = sumSquaredDifferences / (rrIntervalList.size() - 1);
//        return Math.sqrt(meanSquaredDifferences);
    }

    // 표준편차 계산
    private double calculateStandardDeviation(List<Double> intervals) {
        if (intervals.size() < 2) {
            return 0.0; // 데이터가 충분하지 않으면 0 반환
        }

        double mean = intervals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double variance = intervals.stream()
                .mapToDouble(interval -> Math.pow(interval - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance); // 표준편차 반환
    }

    private void uploadHeartRateData(double hrv, int lastHeartRate, double lastRRInterval, String timestamp) {
        // 최저 및 최대 값 계산
        int minHeartRate = heartRateList.stream().min(Integer::compare).orElse(0); // 심박수의 최저값
        int maxHeartRate = heartRateList.stream().max(Integer::compare).orElse(0); // 심박수의 최대값
        double minRRInterval = rrIntervalList.stream().min(Double::compare).orElse(0.0); // R-R 간격의 최저값
        double maxRRInterval = rrIntervalList.stream().max(Double::compare).orElse(0.0); // R-R 간격의 최대값

        // 데이터 맵 구성
        Map<String, Object> heartRateData = new HashMap<>();
        heartRateData.put("errorCount", errorCount);
        heartRateData.put("hrv", hrv); // 계산된 HRV
        heartRateData.put("minHeartRate", minHeartRate); // 심박수 최저값
        heartRateData.put("maxHeartRate", maxHeartRate); // 심박수 최대값
        heartRateData.put("minRRInterval", minRRInterval); // R-R 간격 최저값
        heartRateData.put("maxRRInterval", maxRRInterval); // R-R 간격 최대값
        heartRateData.put("timestamp", timestamp); // 타임스탬프

        // Firebase에 업로드
        String index = String.valueOf(getNextIndex());
        databaseReference.child(index).setValue(heartRateData)
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Heart Rate and HRV data uploaded successfully"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to upload Heart Rate and HRV data", e));

        // 로그 출력
        Log.d(APP_TAG, "Data uploaded. HRV: " + hrv +
                ", Min Heart Rate: " + minHeartRate +
                ", Max Heart Rate: " + maxHeartRate +
                ", Min R-R Interval: " + minRRInterval +
                ", Max R-R Interval: " + maxRRInterval +
                ", Timestamp: " + timestamp);
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
        measureCount = 0;
    }

    public void startDataUpload() {
        shouldUploadData = true;
    }

    public void stopDataUpload() {
        shouldUploadData = false;
    }
}
