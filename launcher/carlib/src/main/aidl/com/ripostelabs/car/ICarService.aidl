package com.ripostelabs.car;

import com.ripostelabs.car.CarStatus;
import com.ripostelabs.car.ICarListener;

// The car API as built so far (os/CARHAL.md "Interface"). Reads need READ, changes need CONTROL.
interface ICarService {
    int apiVersion();
    CarStatus status();
    void registerListener(ICarListener listener);
    void unregisterListener(ICarListener listener);
    void reboot();

    // 2: the MCU link. McuOwner runs in the service; the launcher is its client.
    void openLink();
    void closeLink();
    void setStartup(in byte[] frames);
    boolean setSource(int mode);
    int currentSource();
    void selectCar(String carId);
    void sendMcuFrame(in byte[] frame);
}
