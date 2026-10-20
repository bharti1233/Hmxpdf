package com.hmx.pdf.ui.viewmodel

/**
 * Source selection for the Office editors' Share button. With pending edits the edited copy is
 * mandatory: a failed edit must return null, never fall back to the original file.
 */
internal fun <T> officeShareSource(
    hasEdits: Boolean,
    original: T?,
    edited: () -> T?
): T? = if (hasEdits) edited() else original
