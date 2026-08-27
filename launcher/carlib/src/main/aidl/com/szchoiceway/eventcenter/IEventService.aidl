// Reconstructed from decompiled com.szchoiceway.eventcenter.IEventService (transaction ordinals preserved).
// Methods declared in ascending vendor-ordinal order so generated transaction codes match the device.
// Array/List<String> params default to 'in'; verify direction per method before relying on out-params.
package com.szchoiceway.eventcenter;

import com.szchoiceway.eventcenter.ICommunication;
import com.szchoiceway.eventcenter.ICallbackfn;
import com.szchoiceway.eventcenter.ICameraService;
import android.view.Surface;

interface IEventService {
    void sendMode(int i, boolean z); // ordinal 1
    void sendRadioKey(int i); // ordinal 2
    void sendTVKey(int i); // ordinal 3
    void sendSystemKey(int i); // ordinal 4
    void sendEQMode(int i); // ordinal 5
    void sendUserFreq(int i, boolean z); // ordinal 6
    void beep(); // ordinal 7
    void sendMuteState(boolean z); // ordinal 8
    void sendPlayState(boolean z); // ordinal 9
    void sendSetup(byte b, byte b2); // ordinal 10
    void sendBTState(int i); // ordinal 11
    int getRadioFreq(); // ordinal 12
    int[] getRadioFreqList(); // ordinal 13
    int getRadioBand(); // ordinal 14
    int getRadioNum(); // ordinal 15
    boolean getRadioRDSState(); // ordinal 16
    boolean getRadioPTYState(); // ordinal 17
    int getRadioPTYNum(); // ordinal 18
    String getRadioPTYName(); // ordinal 19
    boolean getRadioAFState(); // ordinal 20
    boolean getRadioTAState(); // ordinal 21
    boolean getRadioSTMonoState(); // ordinal 22
    boolean getRadioDXLOCState(); // ordinal 23
    boolean getRadioAMSState(); // ordinal 24
    boolean getRadioAPSState(); // ordinal 25
    boolean getRadioSteroIconState(); // ordinal 26
    boolean getRadioTPIconState(); // ordinal 27
    boolean getRadioTrafficState(); // ordinal 28
    void setRadioCallback(ICallbackfn iCallbackfn); // ordinal 29
    void setCurModeCallback(int i, ICallbackfn iCallbackfn); // ordinal 30
    void exitCurMode(int i); // ordinal 31
    String getMCUVer(); // ordinal 32
    void putSettingStr(String str, String str2); // ordinal 33
    void putSettingInt(String str, int i); // ordinal 34
    void putSettingLong(String str, long j); // ordinal 35
    void putSettingFloat(String str, float f); // ordinal 36
    void putSettingBoolean(String str, boolean z); // ordinal 37
    void commitSetting(); // ordinal 38
    void appySetting(); // ordinal 39
    boolean getSettingBoolean(String str, boolean z); // ordinal 40
    float getSettingFloat(String str, float f); // ordinal 41
    int getSettingInt(String str, int i); // ordinal 42
    long getSettingLong(String str, long j); // ordinal 43
    String getSettingString(String str, String str2); // ordinal 44
    void sendTouchPos(int i, int i2, boolean z); // ordinal 45
    int getValidMode(); // ordinal 46
    void sendBackcarMod(); // ordinal 47
    void sendCanbusData(in byte[] bArr); // ordinal 48
    byte[] getAirData(int i, in byte[] bArr); // ordinal 49
    void sendAudioValue(in byte[] bArr); // ordinal 50
    void sendBalFadValue(int i, int i2); // ordinal 51
    byte[] getEqBMTFreValue(); // ordinal 52
    boolean getLoudness(); // ordinal 53
    int[] getBALFADValue(); // ordinal 54
    int getEQMode(); // ordinal 55
    ICameraService getCameraService(); // ordinal 56
    void sendSndSWVol(int i); // ordinal 57
    int getSndSWVol(); // ordinal 58
    void notifyModeKeyEvt(int i); // ordinal 59
    void sendBacklight(byte b, byte b2); // ordinal 60
    void sendTVTouchBtnKey(int i); // ordinal 61
    void setValidModeInfor(String str, String str2, boolean z); // ordinal 62
    String getValidModeTitleInfor(); // ordinal 63
    String getValidModeOtherInfor(); // ordinal 64
    boolean getValidPlayState(); // ordinal 65
    void setCamAuxCallback(int i, ICallbackfn iCallbackfn); // ordinal 66
    void sendDVRKey(byte b); // ordinal 67
    void setSystemBrightness(); // ordinal 68
    void exitCamAuxCallback(int i); // ordinal 69
    void initEventState(); // ordinal 70
    void enterUpgradeMode(); // ordinal 71
    void exitUpgradeMode(); // ordinal 72
    boolean sendMcuUpgradeMode(); // ordinal 73
    boolean sendMcuUpgradeData(long j, in byte[] bArr, int i, int i2, boolean z); // ordinal 74
    boolean isUpgradeMode(); // ordinal 75
    void sendFactorySet(); // ordinal 76
    void sendVolState(boolean z, int i); // ordinal 77
    void changeSetup(String str, String str2); // ordinal 78
    void enterCanUpgradeMode(); // ordinal 79
    void exitCanUpgradeMode(); // ordinal 80
    boolean sendCanUpgradeMode(); // ordinal 81
    byte sendCanUpgradeData(in byte[] bArr); // ordinal 82
    boolean isCanUpgradeMode(); // ordinal 83
    byte waitCanEnterUpgradeMode(); // ordinal 84
    void sendWheelKey(int i); // ordinal 85
    void setValidModeFullscrState(boolean z); // ordinal 86
    boolean getValidModeFullscrState(); // ordinal 87
    boolean IsBackCarConneted(); // ordinal 88
    int GetBTStatus(); // ordinal 89
    void setValidModeAllInfor(String str, String str2, String str3, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9); // ordinal 90
    String getValidModeTitleAllInfor(); // ordinal 91
    String getValidModeAblumAllInfor(); // ordinal 92
    String getValidModeArtistAllInfor(); // ordinal 93
    int getValidCurTrack(); // ordinal 94
    int getValidTotTrack(); // ordinal 95
    int getValidCurTime(); // ordinal 96
    int getValidTotTime(); // ordinal 97
    int getValidCurFolder(); // ordinal 98
    int getValidTotFolder(); // ordinal 99
    int getValidLoopMode(); // ordinal 100
    int getValidRepeatMode(); // ordinal 101
    int getValidPlayStatus(); // ordinal 102
    byte getMainVolval(); // ordinal 103
    boolean IsMuteOn(); // ordinal 104
    String getCanVer(); // ordinal 105
    void SetCanVer(String str); // ordinal 106
    void SetSndFreq(byte b, byte b2); // ordinal 107
    void sendSndFreqArray(in byte[] bArr); // ordinal 108
    int getRightSighData(); // ordinal 109
    void WriteGammaParm(int i); // ordinal 110
    int getMacanSignalState(); // ordinal 111
    void SetCanVer2(String str); // ordinal 112
    int getBTMusicStatus(); // ordinal 113
    String getValidModeAblumInfor(); // ordinal 114
    String getValidModeArtistInfor(); // ordinal 115
    void sendSystemReset(); // ordinal 116
    void sendResetDVD(boolean z); // ordinal 117
    boolean IsDiscConneted(); // ordinal 118
    boolean IsBrakeConneted(); // ordinal 119
    boolean IsUSBConnected(); // ordinal 120
    void postRunModeActivity(int i); // ordinal 121
    void setCameraChannel(String str); // ordinal 122
    void openTVout(int i, boolean z); // ordinal 123
    void forceCloseTVout(int i); // ordinal 124
    void sendCanbusUpgradeData(in byte[] bArr); // ordinal 125
    void responseCanUpgradeMode(); // ordinal 126
    void responseCanUpgradeEvent(byte b); // ordinal 127
    void responseCanUpgradeDataEvent(byte b); // ordinal 128
    byte[] getGyroData(); // ordinal 129
    boolean IsHDMIConnected(); // ordinal 130
    void setCanA6DataCallback(ICallbackfn iCallbackfn); // ordinal 131
    void setCanA5DataCallback(ICallbackfn iCallbackfn); // ordinal 132
    void addMessageListener(ICommunication iCommunication); // ordinal 133
    void sendMessageToServer(String str); // ordinal 134
    void sendSoftWareReboot(); // ordinal 135
    String getCanVer2(); // ordinal 136
    boolean getRadioNoPTYState(); // ordinal 137
    int[] getRotateCarPrevX(); // ordinal 138
    int[] getRotateCarPrevY(); // ordinal 139
    void setGyroReset(); // ordinal 140
    int setVirtualDisplaySurface(in Surface surface, int i, int i2); // ordinal 141
    int getVirtualDisplayId(); // ordinal 142
    void setDashBoardCallback(ICallbackfn iCallbackfn); // ordinal 143
    void setUpgradeCallback(ICallbackfn iCallbackfn); // ordinal 144
}
