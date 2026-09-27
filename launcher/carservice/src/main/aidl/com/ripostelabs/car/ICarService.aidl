package com.ripostelabs.car;

import com.ripostelabs.car.CarStatus;
import com.ripostelabs.car.ICarListener;

// First slice of the car API (os/CARHAL.md "Interface"). Reads need READ, changes need CONTROL.
interface ICarService {
    int apiVersion();
    CarStatus status();
    void registerListener(ICarListener listener);
    void unregisterListener(ICarListener listener);
    void reboot();
}
