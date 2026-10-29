package eus.bihurri.kora.sample.wearcheck

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the relay check once per start (`adb shell am start -n eus.bihurri.kora.sample.wearcheck/.CheckActivity`) and
 * mirrors every logged line on screen, so it can be read on the watch.
 */
class CheckActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var output: TextView
    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(output) })

        val app = application as WearCheckApp
        show("$TAG role=${app.role}")
        scope.launch { CheckRunner(app.node, app.wear, ::show).run() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(line: String) {
        Log.i(TAG, line)
        val text = synchronized(lines) {
            lines += line
            lines.joinToString("\n")
        }
        runOnUiThread { output.text = text }
    }
}
