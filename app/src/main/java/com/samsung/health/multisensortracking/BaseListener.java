/*
 * Copyright 2022 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samsung.health.multisensortracking;

import android.os.Handler;
import android.util.Log;

import com.samsung.android.service.health.tracking.HealthTracker;

public class BaseListener {

    // 핸들러: 백그라운드에서 작업을 처리하거나 실행할 때 사용
    private Handler handler;

    // Samsung HealthTracker 객체: 데이터 수집 및 이벤트 관리
    private HealthTracker healthTracker;

    // 핸들러의 동작 여부를 나타내는 플래그
    private boolean isHandlerRunning = false;

    // HealthTracker 이벤트 리스너: 이벤트 처리를 위해 설정
    private HealthTracker.TrackerEventListener trackerEventListener = null;

    // HealthTracker 설정 메서드
    public void setHealthTracker(HealthTracker tracker) {
        healthTracker = tracker;
    }

    // 핸들러 설정 메서드
    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    // 핸들러 동작 상태 설정
    public void setHandlerRunning(boolean handlerRunning) {
        isHandlerRunning = handlerRunning;
    }

    // 트래커 이벤트 리스너 설정
    public void setTrackerEventListener(HealthTracker.TrackerEventListener tracker) {
        trackerEventListener = tracker;
    }

    public void startTracker() {
        if (!isHandlerRunning) {
            handler.post(() -> {
                healthTracker.setEventListener(trackerEventListener);
                setHandlerRunning(true);
            });
        }
    }

    public void stopTracker() {
        if (isHandlerRunning) {
            healthTracker.unsetEventListener();
            setHandlerRunning(false);

            handler.removeCallbacksAndMessages(null);
        }
    }

}
