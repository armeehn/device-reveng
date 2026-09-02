// ICallbackfn.aidl
//
// The gateway's generic callback, as decompiled from
// com.szchoiceway.eventcenter.ICallbackfn (rav4-apps/decompiled). Registered through
// setRadioCallback, setCurModeCallback(int, …) and the CAN data callbacks.
//
// Transaction ordinals are declaration order: notifyEvt = 1, checkIsActive = 2.
// The vendor radio app's own stubs answer checkIsActive() with false.
package com.szchoiceway.eventcenter;

interface ICallbackfn {
    void notifyEvt(int what, int arg1, int arg2, in byte[] data, String str);
    boolean checkIsActive();
}
