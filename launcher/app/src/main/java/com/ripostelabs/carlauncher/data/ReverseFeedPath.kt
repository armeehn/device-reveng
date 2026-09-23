package com.ripostelabs.carlauncher.data

/**
 * ReverseFeedPath — which way the reverse picture comes.
 *
 *     ro.riposte.os.car_owner=1 ──┬─ libais_camera.so loads ──▶ AIS      (the car on 0.2)
 *                                 └─ it does not ─────────────▶ CAMERA2  (farm, desk: no client lib)
 *     anything else ───────────────────────────────────────────▶ CAMERA2  (stock, 0.1: eventcenter owns it)
 */
enum class ReverseFeedPath {
    AIS,
    CAMERA2;

    companion object {
        /** [load] is only tried where the OS owns the car: on stock the vendor gateway holds the camera. */
        fun choose(ownerEnabled: Boolean, load: () -> String?): ReverseFeedPath {
            if (!ownerEnabled) {
                return CAMERA2
            }

            return if (load() == null) AIS else CAMERA2
        }
    }
}
