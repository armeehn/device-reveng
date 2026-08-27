# CarPlay on the GT6-EAU — reality + how to improve it

## Can we build a "proper" CarPlay receiver? No.
- A regular **Apple Developer Program** ($99/yr) only lets you build **iOS apps that show up *on* CarPlay** (running on the iPhone). It does **not** provide CarPlay *receiver* code.
- Being a CarPlay **receiver** (what a head unit does — accept + render the stream from an iPhone) requires Apple's **MFi program** ("Made for iPhone") — a gated hardware‑licensing program (company approval, NDAs, MFi auth chip, confidential specs), effectively restricted to automotive OEM/accessory partners. Not available to individuals/hobbyists.
- So the CarPlay on these units (`com.zjinnova.zlink` / Carlinkit dongles) is a **reverse‑engineered** implementation — which is exactly why it's flaky, and why there's no official code to drop in.

## The flakiness is RF / power / pairing / firmware — not the protocol
Wireless CarPlay = control link over **Bluetooth** + screen/audio stream over **5 GHz Wi‑Fi** between phone and unit. Choppy/disconnecting symptoms almost always come from:
- **2.4 GHz fallback / saturation** — choppy audio is the classic tell of a weak/shared 5 GHz link.
- **iPhone Wi‑Fi conflict** — phone time‑sharing its radio with home/hotspot Wi‑Fi → stutter.
- **Weak accessory‑line power** — voltage sag → module brown‑out → repeated re‑handshake (the "disconnects often").
- **Stale Bluetooth pairing** — reconnect loops.
- **Old receiver app/firmware** — Zlink/Carlinkit push stability fixes.

## Fastest fixes, in order
1. Delete the pairing on **both** phone and unit, re‑pair fresh.
2. On the iPhone, **forget other Wi‑Fi networks** so it locks to the unit's 5 GHz hotspot.
3. **Update** the receiver app/firmware (Zlink, or Carlinkit AutoKit).
4. If it's a plug‑in dongle, try **wired** — stable wired = Wi‑Fi problem; still bad wired = power or unit software.
5. If still bad, use a **licensed hardware dongle** (Carlinkit 5.0 / U2W) to bypass the built‑in receiver entirely.

**Bottom line:** no dev account or new code fixes this — the win is in pairing/RF/power/firmware, or better receiver hardware.
