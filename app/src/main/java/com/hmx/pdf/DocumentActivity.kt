package com.hmx.pdf

/**
 * A document opened from another app, in its own window: its own task and Recents entry (named
 * after the file), separate from the app's main window and from the app that opened it. The
 * external VIEW / SEND filters target this activity directly; MainActivity keeps only a fallback
 * forwarder for explicit intents. Leaving the document closes the window and returns to whatever
 * opened it (#29).
 */
class DocumentActivity : MainActivity() {
    override val isDocumentWindow: Boolean = true
}
