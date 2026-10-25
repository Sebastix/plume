package org.dergigi.feather

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Only a wake-up is stored in WorkManager; signed payloads stay in the durable outbox. */
internal class PostDeliveryWork(private val context: Context) {
    suspend fun schedule(replace: Boolean) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<PostDeliveryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE closes the empty-queue/worker-completion race when another post is saved.
        // Startup recovery uses KEEP, so a worker cold start cannot replace itself.
        WorkManager.getInstance(context).enqueueUniqueWork(NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
            .result.get(30, TimeUnit.SECONDS)
        Unit
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME).result.get(30, TimeUnit.SECONDS)
        Unit
    }

    private companion object { const val NAME = "signed-post-delivery" }
}

/** Android may defer this for battery/Doze; no signer, wallet, or foreground service is needed. */
class PostDeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repo = (applicationContext as FeatherApplication).repository
            if (repo.deliverPostsInBackground()) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Preserve signed events and retry silently; transient relay/storage errors aren't UI errors.
            Result.retry()
        }
    }
}
