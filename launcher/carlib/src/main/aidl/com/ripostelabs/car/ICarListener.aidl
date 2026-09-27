package com.ripostelabs.car;

import com.ripostelabs.car.CarStatus;
import com.ripostelabs.car.McuEvent;

oneway interface ICarListener {
    void onStatus(in CarStatus status);
    void onMcuEvent(in McuEvent event);
}
