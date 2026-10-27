package com.roadvision.roadsense.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes a user-triggered report into the app cache and exposes it through FileProvider. */
object ReportExporter {
    fun write(context: Context, report: String): Uri {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "roadvision-report-${System.currentTimeMillis()}.json")
        file.writeText(report)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
