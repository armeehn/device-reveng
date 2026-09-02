package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.IntentSpec
import com.ripostelabs.carlauncher.carlib.Zlink

/**
 * RAV4-52 — what the quick-launch grid holds, decided without Compose so the CarPlay
 * reflow is a plain unit test.
 *
 * Idle: the pinned + fill apps, row-major. Projected (a phone is on CarPlay / Android Auto):
 * the CarPlay tile gives way to a full-width row of `REQ_SPEC_FUNC_CMD` shortcuts and the
 * remaining apps take the rows left.
 *
 * ```
 *  idle                      projected
 *  ┌────┬────┬────┐          ┌────┬────┬────┬────┬────┐
 *  │ CP │ Cl │ Ph │          │Siri│Maps│Musc│Now │Home│
 *  ├────┼────┼────┤   ──▶    ├────┴─┬──┴──┬─┴────┴────┤
 *  │ f1 │ f2 │ f3 │          │  Cl  │ Ph  │    f1     │
 *  └────┴────┴────┘          └──────┴─────┴───────────┘
 * ```
 *
 * The lowest-priority fills drop while projected: the grid height is fixed, and a third
 * row would push every target under the driving minimum.
 */

/** The shortcuts offered while projected. Each is one [Zlink.request] broadcast. */
enum class CarPlayAction(val feature: Zlink.Feature, val label: String) {
    SIRI(Zlink.Feature.SIRI, "Siri"),
    MAPS(Zlink.Feature.MAPS, "Maps"),
    MUSIC(Zlink.Feature.MUSIC, "Music"),
    NOW_PLAYING(Zlink.Feature.NOW_PLAYING, "Now playing"),
    HOME(Zlink.Feature.HOME, "Home"),
    ;

    /** ⚠ UNVERIFIED whether Zlink 5.4.62 honours the code from a sender other than the gateway. */
    fun intent(): IntentSpec = Zlink.request(feature)
}

/** One grid cell: an app to launch, or a projected-phone shortcut. */
sealed interface QuickSlot<out T> {
    data class App<T>(val app: T) : QuickSlot<T>
    data class Action(val action: CarPlayAction) : QuickSlot<Nothing>
}

data class QuickLayout<T>(val rows: List<List<QuickSlot<T>>>) {
    /** Row-major: the index space of the SWC Quick focus ring. */
    val slots: List<QuickSlot<T>> = rows.flatten()
}

enum class Projection { IDLE, PROJECTED }

/**
 * Lay [apps] out in [columns] × [maxRows]. [isCarPlay] names the tile that reflows into the
 * shortcut row while [projection] is [Projection.PROJECTED]; with no such tile the grid is
 * left alone, projected or not.
 */
fun <T> quickLayout(
    apps: List<T>,
    columns: Int,
    maxRows: Int,
    projection: Projection,
    isCarPlay: (T) -> Boolean,
): QuickLayout<T> {
    val carPlayIndex = apps.indexOfFirst(isCarPlay)
    if (projection == Projection.IDLE || carPlayIndex < 0) {
        return QuickLayout(apps.map { QuickSlot.App(it) }.chunked(columns).take(maxRows))
    }

    val actions = CarPlayAction.entries.map { QuickSlot.Action(it) }
    val rest = apps
        .filterIndexed { index, _ -> index != carPlayIndex }
        .map { QuickSlot.App(it) }
        .chunked(columns)
    return QuickLayout((listOf(actions) + rest).take(maxRows))
}
