package com.ripostelabs.carlauncher.tuner;

import com.ripostelabs.carlauncher.tuner.ITunerCallback;
import com.ripostelabs.carlauncher.tuner.TunerState;

/**
 * The tuner the launcher owns, for the com.ripostelabs.* suite (RAV4-97). Bind with action
 * `com.ripostelabs.carlauncher.tuner.ITuner` on package `com.ripostelabs.carlauncher`.
 *
 * Transaction codes, for a client built without the aidl tool (declaration order + 1):
 * claim 1, release 2, isClaimed 3, sendKey 4, tune 5, getState 6, registerCallback 7,
 * unregisterCallback 8, selectPreset 9, storePreset 10. Descriptor
 * `com.ripostelabs.carlauncher.tuner.ITuner`.
 */
interface ITuner {
    /** RadioSource.claim(): sendMode(SRC_RADIO). True when the tuner is now the source. */
    boolean claim();
    /** RadioSource.release(): exitCurMode(SRC_RADIO). */
    void release();
    /** getValidMode() == SRC_RADIO. */
    boolean isClaimed();
    /**
     * OP_RADIO_KEY. 1..6 preset recall, 13 scan, 14/15 step, 16/17 seek, 18 AMS, 19 st/mono,
     * 20 dx/loc, 21 AF, 23 TA, 30 FM, 31 AM (CarService.RADIO_KEY_*).
     */
    void sendKey(int key);
    /** OP_USER_FREQ. freq in tuner units (FM 10 kHz, AM 1 kHz), fm = the band. */
    void tune(int freq, boolean fm);
    TunerState getState();
    void registerCallback(ITunerCallback cb);
    void unregisterCallback(ITunerCallback cb);
    /**
     * The MCU's own preset banks (`02 64 slot` / `02 65 slot`, the vendor radio's cmd 100/101).
     * slot is 0..41 into TunerState.stationList: FM banks 0..17, AM 18..41.
     */
    void selectPreset(int slot);
    void storePreset(int slot);
}
