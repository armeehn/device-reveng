package com.ripostelabs.carlauncher.tuner;

import com.ripostelabs.carlauncher.tuner.TunerState;

/**
 * What the launcher pushes to a bound radio (RAV4-97). Transaction codes, for a client built
 * without the aidl tool: onState 1, onSourceLost 2, onReclaim 3.
 */
interface ITunerCallback {
    /** Every RadioStateHolder fold. */
    oneway void onState(in TunerState state);
    /** Another source took the MCU (the vendor's EVT_MODE_CHANGE away from SRC_RADIO). */
    oneway void onSourceLost();
    /** Reserved: the launcher asks the radio to claim again. Not fired yet. */
    oneway void onReclaim();
}
