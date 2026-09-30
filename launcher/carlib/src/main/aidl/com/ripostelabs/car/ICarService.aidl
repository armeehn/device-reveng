package com.ripostelabs.car;

import com.ripostelabs.car.CarStatus;
import com.ripostelabs.car.ICarListener;
import com.ripostelabs.car.ReverseState;

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

    // 4: the reverse camera around the picture. The camera session stays in the client; the
    // service owns the line and the PR2000 decoder, so both survive the launcher.
    const int DECODER_FORCE_STREAMABLE = 1;     // c0 + v4: a fixed mode the AIS server can size
    const int DECODER_REDETECT = 2;             // r, c1, v0: reset and auto-detect, as stock does
    ReverseState reverseState();                // READ
    void setDecoderMode(int mode);              // 0 auto .. 8, persisted; CONTROL
    void decoderSignal(int action);             // DECODER_*; CONTROL

    // 5: the nav bar as a system window that reserves the navigationBars inset, so apps lay
    // out above it. The launcher's NavBarPolicy still decides the state.
    const int NAV_HIDDEN = 0;
    const int NAV_HANDLE = 1;                   // folded: a thin touch edge
    const int NAV_EXPANDED = 2;                 // Back, Home, Apps
    void setNavBar(int state, in int[] colors); // surface, onSurface, primary (ARGB); CONTROL

    // 6: what a short POWER press does, as Sys_Power_key_set stores it. SCREEN_OFF keeps the
    // MCU on its source (no SRC_POWEROFF burst); the launcher blacks the panel itself.
    const int POWER_KEY_SCREEN_OFF = 0;
    const int POWER_KEY_STANDBY = 1;             // the default: the vendor's power-off burst
    void setPowerKey(int mode);                 // CONTROL
}
