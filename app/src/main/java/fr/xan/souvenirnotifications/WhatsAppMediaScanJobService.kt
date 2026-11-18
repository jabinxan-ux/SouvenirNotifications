package fr.xan.souvenirnotifications

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent

class WhatsAppMediaScanJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (WhatsAppMediaImporter.savedTreeUri(this) == null) {
            jobFinished(params, false)
            return false
        }
        WhatsAppMediaImporter.scanAsync(this, force = false) { result ->
            if (result.imported > 0) {
                sendBroadcast(Intent(ArchiveEvents.ACTION_UPDATED).setPackage(packageName))
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
