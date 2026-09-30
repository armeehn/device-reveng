package com.ripostelabs.carlauncher.ui.settings

import com.ripostelabs.carlauncher.carlib.McuFactorySet

/**
 * The unit's factory value for a `0F` row, so a toggle with no saved row shows what the
 * frame sends (McuFactorySet.UNIT_ROWS), not a guess. Example: sleep switch reads on.
 */
internal fun factoryDefault(key: String): Boolean = McuFactorySet.UNIT_ROWS[key] == "1"
