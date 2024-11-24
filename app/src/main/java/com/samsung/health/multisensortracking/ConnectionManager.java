package com.samsung.health.multisensortracking;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.samsung.android.service.health.tracking.ConnectionListener;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;

import java.util.List;

public class ConnectionManager {
    private final static String TAG = "Connection Manager";
    private final ConnectionObserver connectionObserver; // 연결 상태를 알리기 위한 옵저버
    private HealthTrackingService healthTrackingService = null; // Health Tracking Service 참조

    // ConnectionListener: 연결 상태를 처리하기 위한 리스너
    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override
        public void onConnectionSuccess() {
            Log.i(TAG, "Connected");
            connectionObserver.onConnectionResult(R.string.ConnectedToHs); // 연결 성공 알림

            // 심박수 트래커 지원 여부 확인
            if (!isHeartRateAvailable(healthTrackingService)) {
                Log.i(TAG, "Device does not support Heart Rate tracking");
                connectionObserver.onConnectionResult(R.string.NoHrSupport); // 지원 불가 알림
            }
        }

        @Override
        public void onConnectionEnded() {
            Log.i(TAG, "Disconnected"); // 연결 종료 알림
        }

        @Override
        public void onConnectionFailed(HealthTrackerException e) {
            connectionObserver.onError(e); // 연결 실패 시 예외 전달
        }
    };

    // ConnectionManager 생성자
    ConnectionManager(ConnectionObserver observer) {
        connectionObserver = observer; // 연결 상태 옵저버 설정
    }

    // Health Tracking Service 연결
    public void connect(Context context) {
        healthTrackingService = new HealthTrackingService(connectionListener, context); // HealthTrackingService 초기화
        healthTrackingService.connectService(); // 서비스 연결 시도
    }

    // Health Tracking Service 연결 해제
    public void disconnect() {
        if (healthTrackingService != null)
            healthTrackingService.disconnectService();
    }

    // 심박수 트래커 초기화
    public void initHeartRate(HeartRateListener heartRateListener) {
        final HealthTracker healthTracker;
        healthTracker = healthTrackingService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS); // 심박수 트래커 가져오기
        heartRateListener.setHealthTracker(healthTracker); // HeartRateListener에 트래커 설정
        setHandlerForBaseListener(heartRateListener); // 메인 스레드 핸들러 설정
    }

    // BaseListener에 메인 스레드 핸들러 설정
    private void setHandlerForBaseListener(BaseListener baseListener) {
        baseListener.setHandler(new Handler(Looper.getMainLooper())); // 메인 스레드 핸들러 지정
    }

    // 심박수 트래커 지원 여부 확인
    private boolean isHeartRateAvailable(@NonNull HealthTrackingService healthTrackingService) {
        final List<HealthTrackerType> availableTrackers = healthTrackingService.getTrackingCapability().getSupportHealthTrackerTypes(); // 지원되는 트래커 목록 가져오기
        return availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS); // 심박수 트래커 지원 여부 반환
    }

}
