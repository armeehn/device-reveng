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

    // 3: power. factoryReset takes a RESET_* scope; the owner has decided only this one so far.
    const int RESET_DATA_WIPE = 1;              // Android's own factory reset: /data wiped in recovery
    void factoryReset(int scope);
}
