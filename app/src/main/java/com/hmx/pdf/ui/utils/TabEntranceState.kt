package com.hmx.pdf.ui.utils

/**
 * Tracks the first time each bottom tab has been composed in this process.
 *
 * A tab switch pops the old destination and composes a new one, so a `remember { false }` gate
 * replayed the full stagger every time the user returned to a tab. That meant the tab bar's own
 * 180 ms fade ran underneath a second 0.6 s entrance before the screen reached full opacity.
 * First visits still animate; later visits appear with the navigation transition alone.
 */
internal object TabEntranceState {
    var homeShown = false
    var toolsShown = false
    var settingsShown = false
}
