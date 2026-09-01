package my.torrstream.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import my.torrstream.app.BuildConfig
import my.torrstream.app.channels.WatchNext
import my.torrstream.app.helpers.Helpers.isTvContentProviderAvailable
import my.torrstream.app.sched.Scheduler

private const val TAG: String = "HomeWatch"

@RequiresApi(Build.VERSION_CODES.O)
class HomeWatch() : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action

        if (action == null || !isTvContentProviderAvailable)
            return

        val watchNextId = intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)

        when (action) {

            TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> {
                if (BuildConfig.DEBUG)
                    Log.d(TAG, "ACTION_INITIALIZE_PROGRAMS received")
                Scheduler.scheduleUpdate(true)
            }

            TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                // The user explicitly removed the row from Watch Next — drop it and don't
                // let the next resync bring it back.
                if (watchNextId != -1L) {
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED, watch-next $watchNextId")
                    WatchNext.removeByProgramId(watchNextId)
                }
            }
        }
    }
}
