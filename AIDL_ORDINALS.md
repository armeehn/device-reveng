# IEventService — vendor Binder ordinal map (reconstructed)

Descriptor: `com.szchoiceway.eventcenter.IEventService` — 144 remotable methods.

| ordinal | method | returns | params |
|--:|---|---|---|
| 1 | `sendMode` | void | int i, boolean z |
| 2 | `sendRadioKey` | void | int i |
| 3 | `sendTVKey` | void | int i |
| 4 | `sendSystemKey` | void | int i |
| 5 | `sendEQMode` | void | int i |
| 6 | `sendUserFreq` | void | int i, boolean z |
| 7 | `beep` | void | — |
| 8 | `sendMuteState` | void | boolean z |
| 9 | `sendPlayState` | void | boolean z |
| 10 | `sendSetup` | void | byte b, byte b2 |
| 11 | `sendBTState` | void | int i |
| 12 | `getRadioFreq` | int | — |
| 13 | `getRadioFreqList` | int[] | — |
| 14 | `getRadioBand` | int | — |
| 15 | `getRadioNum` | int | — |
| 16 | `getRadioRDSState` | boolean | — |
| 17 | `getRadioPTYState` | boolean | — |
| 18 | `getRadioPTYNum` | int | — |
| 19 | `getRadioPTYName` | String | — |
| 20 | `getRadioAFState` | boolean | — |
| 21 | `getRadioTAState` | boolean | — |
| 22 | `getRadioSTMonoState` | boolean | — |
| 23 | `getRadioDXLOCState` | boolean | — |
| 24 | `getRadioAMSState` | boolean | — |
| 25 | `getRadioAPSState` | boolean | — |
| 26 | `getRadioSteroIconState` | boolean | — |
| 27 | `getRadioTPIconState` | boolean | — |
| 28 | `getRadioTrafficState` | boolean | — |
| 29 | `setRadioCallback` | void | ICallbackfn iCallbackfn |
| 30 | `setCurModeCallback` | void | int i, ICallbackfn iCallbackfn |
| 31 | `exitCurMode` | void | int i |
| 32 | `getMCUVer` | String | — |
| 33 | `putSettingStr` | void | String str, String str2 |
| 34 | `putSettingInt` | void | String str, int i |
| 35 | `putSettingLong` | void | String str, long j |
| 36 | `putSettingFloat` | void | String str, float f |
| 37 | `putSettingBoolean` | void | String str, boolean z |
| 38 | `commitSetting` | void | — |
| 39 | `appySetting` | void | — |
| 40 | `getSettingBoolean` | boolean | String str, boolean z |
| 41 | `getSettingFloat` | float | String str, float f |
| 42 | `getSettingInt` | int | String str, int i |
| 43 | `getSettingLong` | long | String str, long j |
| 44 | `getSettingString` | String | String str, String str2 |
| 45 | `sendTouchPos` | void | int i, int i2, boolean z |
| 46 | `getValidMode` | int | — |
| 47 | `sendBackcarMod` | void | — |
| 48 | `sendCanbusData` | void | in byte[] bArr |
| 49 | `getAirData` | byte[] | int i, in byte[] bArr |
| 50 | `sendAudioValue` | void | in byte[] bArr |
| 51 | `sendBalFadValue` | void | int i, int i2 |
| 52 | `getEqBMTFreValue` | byte[] | — |
| 53 | `getLoudness` | boolean | — |
| 54 | `getBALFADValue` | int[] | — |
| 55 | `getEQMode` | int | — |
| 56 | `getCameraService` | ICameraService | — |
| 57 | `sendSndSWVol` | void | int i |
| 58 | `getSndSWVol` | int | — |
| 59 | `notifyModeKeyEvt` | void | int i |
| 60 | `sendBacklight` | void | byte b, byte b2 |
| 61 | `sendTVTouchBtnKey` | void | int i |
| 62 | `setValidModeInfor` | void | String str, String str2, boolean z |
| 63 | `getValidModeTitleInfor` | String | — |
| 64 | `getValidModeOtherInfor` | String | — |
| 65 | `getValidPlayState` | boolean | — |
| 66 | `setCamAuxCallback` | void | int i, ICallbackfn iCallbackfn |
| 67 | `sendDVRKey` | void | byte b |
| 68 | `setSystemBrightness` | void | — |
| 69 | `exitCamAuxCallback` | void | int i |
| 70 | `initEventState` | void | — |
| 71 | `enterUpgradeMode` | void | — |
| 72 | `exitUpgradeMode` | void | — |
| 73 | `sendMcuUpgradeMode` | boolean | — |
| 74 | `sendMcuUpgradeData` | boolean | long j, in byte[] bArr, int i, int i2, boolean z |
| 75 | `isUpgradeMode` | boolean | — |
| 76 | `sendFactorySet` | void | — |
| 77 | `sendVolState` | void | boolean z, int i |
| 78 | `changeSetup` | void | String str, String str2 |
| 79 | `enterCanUpgradeMode` | void | — |
| 80 | `exitCanUpgradeMode` | void | — |
| 81 | `sendCanUpgradeMode` | boolean | — |
| 82 | `sendCanUpgradeData` | byte | in byte[] bArr |
| 83 | `isCanUpgradeMode` | boolean | — |
| 84 | `waitCanEnterUpgradeMode` | byte | — |
| 85 | `sendWheelKey` | void | int i |
| 86 | `setValidModeFullscrState` | void | boolean z |
| 87 | `getValidModeFullscrState` | boolean | — |
| 88 | `IsBackCarConneted` | boolean | — |
| 89 | `GetBTStatus` | int | — |
| 90 | `setValidModeAllInfor` | void | String str, String str2, String str3, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9 |
| 91 | `getValidModeTitleAllInfor` | String | — |
| 92 | `getValidModeAblumAllInfor` | String | — |
| 93 | `getValidModeArtistAllInfor` | String | — |
| 94 | `getValidCurTrack` | int | — |
| 95 | `getValidTotTrack` | int | — |
| 96 | `getValidCurTime` | int | — |
| 97 | `getValidTotTime` | int | — |
| 98 | `getValidCurFolder` | int | — |
| 99 | `getValidTotFolder` | int | — |
| 100 | `getValidLoopMode` | int | — |
| 101 | `getValidRepeatMode` | int | — |
| 102 | `getValidPlayStatus` | int | — |
| 103 | `getMainVolval` | byte | — |
| 104 | `IsMuteOn` | boolean | — |
| 105 | `getCanVer` | String | — |
| 106 | `SetCanVer` | void | String str |
| 107 | `SetSndFreq` | void | byte b, byte b2 |
| 108 | `sendSndFreqArray` | void | in byte[] bArr |
| 109 | `getRightSighData` | int | — |
| 110 | `WriteGammaParm` | void | int i |
| 111 | `getMacanSignalState` | int | — |
| 112 | `SetCanVer2` | void | String str |
| 113 | `getBTMusicStatus` | int | — |
| 114 | `getValidModeAblumInfor` | String | — |
| 115 | `getValidModeArtistInfor` | String | — |
| 116 | `sendSystemReset` | void | — |
| 117 | `sendResetDVD` | void | boolean z |
| 118 | `IsDiscConneted` | boolean | — |
| 119 | `IsBrakeConneted` | boolean | — |
| 120 | `IsUSBConnected` | boolean | — |
| 121 | `postRunModeActivity` | void | int i |
| 122 | `setCameraChannel` | void | String str |
| 123 | `openTVout` | void | int i, boolean z |
| 124 | `forceCloseTVout` | void | int i |
| 125 | `sendCanbusUpgradeData` | void | in byte[] bArr |
| 126 | `responseCanUpgradeMode` | void | — |
| 127 | `responseCanUpgradeEvent` | void | byte b |
| 128 | `responseCanUpgradeDataEvent` | void | byte b |
| 129 | `getGyroData` | byte[] | — |
| 130 | `IsHDMIConnected` | boolean | — |
| 131 | `setCanA6DataCallback` | void | ICallbackfn iCallbackfn |
| 132 | `setCanA5DataCallback` | void | ICallbackfn iCallbackfn |
| 133 | `addMessageListener` | void | ICommunication iCommunication |
| 134 | `sendMessageToServer` | void | String str |
| 135 | `sendSoftWareReboot` | void | — |
| 136 | `getCanVer2` | String | — |
| 137 | `getRadioNoPTYState` | boolean | — |
| 138 | `getRotateCarPrevX` | int[] | — |
| 139 | `getRotateCarPrevY` | int[] | — |
| 140 | `setGyroReset` | void | — |
| 141 | `setVirtualDisplaySurface` | int | Surface surface, int i, int i2 |
| 142 | `getVirtualDisplayId` | int | — |
| 143 | `setDashBoardCallback` | void | ICallbackfn iCallbackfn |
| 144 | `setUpgradeCallback` | void | ICallbackfn iCallbackfn |
