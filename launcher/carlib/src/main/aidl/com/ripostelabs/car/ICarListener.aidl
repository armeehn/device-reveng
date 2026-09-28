package com.ripostelabs.car;

import com.ripostelabs.car.CarStatus;
import com.ripostelabs.car.McuEvent;
import com.ripostelabs.car.ReverseState;

oneway interface ICarListener {
    void onStatus(in CarStatus status);
    void onMcuEvent(in McuEvent event);
    void onReverse(in ReverseState state);      // 4: every edge of the line
}
