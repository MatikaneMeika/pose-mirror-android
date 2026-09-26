package com.posemirror.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.posemirror.app.prefs.NetworkCondition
import com.posemirror.app.prefs.UpdateFrequency
import java.util.concurrent.TimeUnit

/** Schedules / cancels the unique periodic index-update work. */
object UpdateScheduler {
    const val WORK_NAME = "pose-mirror-index-update"

    fun schedule(
        context: Context,
        frequency: UpdateFrequency,
        batchCount: Int,
        network: NetworkCondition
    ) {
        val days = frequency.days ?: return // OFF
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only, per spec
            .setRequiresCharging(network == NetworkCondition.WIFI_CHARGING)
            .build()
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(days, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInputData(workDataOf(UpdateWorker.KEY_BATCH to batchCount))
            .addTag("index-update")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
