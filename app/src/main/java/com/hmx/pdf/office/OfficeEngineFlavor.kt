package com.hmx.pdf.office

import android.content.Context

/** Shared flavor: the engine is downloaded from the pinned release by [DownloadOfficeEngineInstaller]. */
object OfficeEngineFlavor {
    const val available: Boolean = true
    fun createInstaller(context: Context): OfficeEngineInstaller = DownloadOfficeEngineInstaller(context)
}
