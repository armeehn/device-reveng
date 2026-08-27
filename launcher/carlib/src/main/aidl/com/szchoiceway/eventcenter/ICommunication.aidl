// ICommunication.aidl
//
// Reconstructed from CAR_API.md §3.1 (decompiled ICommunication.java).
// Descriptor MUST be exactly "com.szchoiceway.eventcenter.ICommunication" — that is
// fixed by this file's package + interface name, so do not move/rename it.
//
// This is the callback the gateway invokes; register it with
// IEventService.addMessageListener(ICommunication).
package com.szchoiceway.eventcenter;

interface ICommunication {
    // Gateway pushes a text status/event line (see the LocalSocket text protocol,
    // CAR_API §3.3: "CURRENT_MODE_INFO:", "CAR_ACC_STATUS:", ...).
    void notifyMessage(String message);

    // Gateway polls whether this listener is still alive.
    boolean checkIsActive();
}
