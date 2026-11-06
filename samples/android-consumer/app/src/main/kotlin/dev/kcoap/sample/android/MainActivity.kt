package dev.kcoap.sample.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the [KcoapTour] once and shows what it found; the same text goes to logcat under the tag `kcoap`.
 * The only Android code of the sample: everything else is plain Kotlin on kcoap's jvm artifacts.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(PADDING, PADDING, PADDING, PADDING)
            text = getString(R.string.running)
        }
        setContentView(ScrollView(this).apply { addView(output) })

        // The crypto backend and the OSCORE contexts are the process's, shared by every MainActivity.
        val app = application as SampleApplication
        val tour = KcoapTour(app.crypto, CRYPTO_SETUP, app.oscore)
        scope.launch {
            val report = try {
                tour.run(scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An Error too: a class or method missing on this API level shows up here, not as a crash.
                "kcoap failed: ${Log.getStackTraceString(e)}"
            }
            Log.i(TAG, report)
            runOnUiThread { output.text = report }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "kcoap"
        const val PADDING = 32
    }
}
