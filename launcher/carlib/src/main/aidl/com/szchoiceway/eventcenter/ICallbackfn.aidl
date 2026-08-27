// ICallbackfn.aidl
//
// Reconstructed callback interface referenced by the radio/EQ/CAN setter methods
// in IEventService (CAR_API §3.2, e.g. setRadioCallback(ICallbackfn),
// setCanA5DataCallback(ICallbackfn), setCurModeCallback(int, ICallbackfn)).
//
// TODO: the exact method signature(s) of ICallbackfn were NOT transcribed in
// CAR_API.md — the real shape lives in the decompiled ICallbackfn.java. The single
// callback below is a best-guess placeholder that is enough for the interface to
// compile and for read-only binding. Verify against the device before relying on it.
package com.szchoiceway.eventcenter;

interface ICallbackfn {
    // Placeholder: gateway delivers an event id + payload. Adjust to the real signature.
    void onCallback(int what, in byte[] data);
}
