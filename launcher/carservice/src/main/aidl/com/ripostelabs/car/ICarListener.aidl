package com.ripostelabs.car;

import com.ripostelabs.car.CarStatus;

oneway interface ICarListener {
    void onStatus(in CarStatus status);
}
