// IEventService.aidl
//
// Reconstructed vendor control interface for the bound EventService (CAR_API §3.1/§3.2).
// The AIDL package + interface name fix the Binder descriptor to
//   "com.szchoiceway.eventcenter.IEventService"
// which MUST match the real service (IEventService.java:960) for asInterface() to work.
//
// IMPORTANT / TODO:
//   * Only a REPRESENTATIVE SUBSET of the ~200 methods in §3.2 is declared here.
//   * AIDL is positional: the transaction code of each method is its ordinal in the
//     REAL interface. Because we do not have the full ordered method list from
//     IEventService.java, the ordinals below almost certainly DO NOT line up with the
//     device. Read-only binding (getService/asInterface) works regardless, but any
//     actual transact() will hit the wrong method on the server until this file is
//     regenerated from the real decompiled IEventService.java (preserve declaration
//     ORDER exactly). Treat every call as unverified until then.
//   * Signatures are best-effort from the method table in CAR_API §3.2.
package com.szchoiceway.eventcenter;

import com.szchoiceway.eventcenter.ICommunication;
import com.szchoiceway.eventcenter.ICallbackfn;

interface IEventService {
    // ---- Mode / source ----------------------------------------------------
    void sendMode(int mode, boolean flag);
    int  getValidMode();
    void exitCurMode(int mode);
    void postRunModeActivity(int mode);
    void notifyModeKeyEvt(int key);
    void setCurModeCallback(int mode, ICallbackfn cb);

    // ---- Radio ------------------------------------------------------------
    void sendRadioKey(int key);
    int  getRadioFreq();
    int  getRadioBand();
    int  getRadioNum();
    void sendUserFreq(int freq, boolean save);
    void setRadioCallback(ICallbackfn cb);

    // ---- Audio / EQ / volume ---------------------------------------------
    void sendEQMode(int mode);
    int  getEQMode();
    void sendMuteState(boolean mute);
    boolean IsMuteOn();
    void sendVolState(boolean up, int step);
    int  getMainVolval();
    void beep();

    // ---- Keys -------------------------------------------------------------
    void sendSystemKey(int key);
    void sendWheelKey(int key);

    // ---- Climate / CAN ----------------------------------------------------
    void getAirData(int type, inout byte[] buf);
    void sendCanbusData(in byte[] data);
    String getCanVer();

    // ---- Car state getters (read-only, safe from a normal app) -----------
    boolean IsBackCarConneted();
    boolean IsBrakeConneted();
    boolean IsHDMIConnected();
    boolean IsUSBConnected();

    // ---- Settings passthrough (typed front-end to the SysVar store) -------
    int    getSettingInt(String key, int def);
    long   getSettingLong(String key, long def);
    boolean getSettingBoolean(String key, boolean def);
    String getSettingString(String key, String def);
    void   putSettingInt(String key, int value);
    void   putSettingStr(String key, String value);
    void   commitSetting();

    // ---- Power / system ---------------------------------------------------
    void sendSoftWareReboot();

    // ---- Listener ---------------------------------------------------------
    void addMessageListener(ICommunication listener);
}
