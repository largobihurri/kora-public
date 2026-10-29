package eus.bihurri.kora.sample.provider

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.mcp.http.DeniedAttempt
import eus.bihurri.kora.mcp.http.Pairing
import eus.bihurri.kora.tailnet.KoraTailnet
import eus.bihurri.kora.tailnet.TailnetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * The provider's screen: what it provides, plus the "Agent access" section of the tailnet device test. The section
 * exists only to drive `kora-tailnet` by hand (switch, pairing, revoke, refused requests); see the README.
 */
@SuppressLint("SetTextI18n") // a test screen: English literals on purpose
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var accessSwitch: Switch
    private lateinit var tokenBox: LinearLayout
    private lateinit var tokenView: TextView
    private lateinit var pairingsBox: LinearLayout
    private lateinit var deniedView: TextView
    private var observing: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(text("Kora sample provider\nnode: ${KoraAndroid.node.nodeId}\nProvides media.library and media.playback."))
        column.addView(heading("Agent access"))
        column.addView(buildSwitch())
        statusView = text("Off")
        column.addView(statusView)
        column.addView(buildIdleChoice())
        column.addView(Button(this).apply {
            text = "Pair agent"
            setOnClickListener { askPairingName() }
        })
        tokenBox = buildTokenBox()
        column.addView(tokenBox)
        column.addView(heading("Pairings"))
        pairingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(pairingsBox)
        column.addView(heading("Last refused requests"))
        deniedView = text("none")
        column.addView(deniedView)
        // Edge-to-edge is enforced on current Android: keep the content clear of the system bars.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        observing = scope
        scope.launch { KoraTailnet.status.collect { status -> runOnUiThread { showStatus(status) } } }
        scope.launch { TailnetSample.denied.collect { attempts -> runOnUiThread { showDenied(attempts) } } }
        refreshPairings()
    }

    override fun onStop() {
        observing?.cancel()
        observing = null
        // The token is shown once and is not kept: leaving the screen forgets it.
        hideToken()
        super.onStop()
    }

    // --- the switch --------------------------------------------------------------------------------------------

    private fun buildSwitch() = Switch(this).also { toggle ->
        accessSwitch = toggle
        toggle.text = "Agent access over Tailscale (port ${TailnetSample.PORT})"
        // A click only: showStatus() sets isChecked programmatically and must not trigger start() or stop().
        toggle.setOnClickListener { if (toggle.isChecked) startAccess() else KoraTailnet.stop() }
    }

    private fun startAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The result handler starts the listener either way: without the permission it runs with no visible notification.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            KoraTailnet.start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("Notifications are off: the service runs without a visible notification")
        }
        KoraTailnet.start()
    }

    private fun showStatus(status: TailnetStatus) {
        accessSwitch.isChecked = status !is TailnetStatus.Off
        statusView.text = when (status) {
            TailnetStatus.Off -> "Off"
            TailnetStatus.WaitingForTailnet -> "Waiting for tailnet (is Tailscale connected on this phone?)"
            is TailnetStatus.Listening -> "Listening: ${status.url}"
            is TailnetStatus.Error -> "Error: ${status.reason}"
        }
    }

    /** The auto-off period is fixed at install, so changing it reinstalls (the switch goes off) and recreates the screen. */
    private fun buildIdleChoice() = CheckBox(this).apply {
        text = "Auto-off after ${TailnetSample.SHORT_IDLE_TIMEOUT} (test); otherwise ${TailnetSample.IDLE_TIMEOUT}"
        isChecked = TailnetSample.isShortIdle(this@MainActivity)
        setOnClickListener {
            val shortIdle = isChecked
            Thread {
                (application as ProviderApp).setShortIdle(shortIdle)
                runOnUiThread { recreate() }
            }.start()
        }
    }

    // --- pairing -----------------------------------------------------------------------------------------------

    private fun askPairingName() {
        val input = EditText(this).apply {
            hint = "Agent name, e.g. Claude on my-laptop"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Pair agent")
            .setView(input)
            .setPositiveButton("Pair") { _, _ -> pair(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pair(name: String) {
        Thread {
            try {
                val issued = KoraTailnet.pair(name)
                runOnUiThread { showToken(issued.pairing.name, issued.token) }
            } catch (e: IllegalArgumentException) {
                runOnUiThread { toast(e.message ?: "Invalid name") }
            } catch (e: IOException) {
                runOnUiThread { toast("Could not save the pairing: ${e.message}") }
            }
            refreshPairings()
        }.start()
    }

    /**
     * The only place the token appears (test sample): a selectable view, copied from the screen into the agent's
     * configuration. It is never logged, stored in a file or kept in a field, and it disappears with "Hide" or when
     * the screen is left. Only the token's hash is stored by the library, so it cannot be shown again: pair again.
     */
    private fun showToken(name: String, token: String) {
        tokenBox.findViewWithTag<TextView>(TAG_TOKEN_LABEL).text = "Token for '$name' (shown once; select to copy):"
        tokenView.text = token
        tokenBox.keepScreenOn = true
        tokenBox.visibility = View.VISIBLE
    }

    private fun hideToken() {
        tokenView.text = ""
        tokenBox.keepScreenOn = false
        tokenBox.visibility = View.GONE
    }

    private fun buildTokenBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(text("").apply { tag = TAG_TOKEN_LABEL })
        tokenView = text("").apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        addView(tokenView)
        addView(Button(this@MainActivity).apply {
            text = "Hide token"
            setOnClickListener { hideToken() }
        })
    }

    private fun refreshPairings() {
        Thread {
            val pairings = KoraTailnet.pairings()
            runOnUiThread { showPairings(pairings) }
        }.start()
    }

    private fun showPairings(pairings: List<Pairing>) {
        pairingsBox.removeAllViews()
        if (pairings.isEmpty()) pairingsBox.addView(text("none"))
        for (pairing in pairings) {
            pairingsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("${pairing.name}\n${pairing.id}").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(this@MainActivity).apply {
                    text = "Revoke"
                    setOnClickListener { revoke(pairing) }
                })
            })
        }
    }

    private fun revoke(pairing: Pairing) {
        Thread {
            try {
                KoraTailnet.revoke(pairing.id)
            } catch (e: IOException) {
                runOnUiThread { toast("Revoked, but the file could not be written: ${e.message}. Revoke again.") }
            }
            refreshPairings()
        }.start()
    }

    private fun showDenied(attempts: List<DeniedAttempt>) {
        val format = DateFormat.getTimeInstance()
        deniedView.text = if (attempts.isEmpty()) {
            "none"
        } else {
            attempts.joinToString("\n") {
                val more = if (it.suppressed > 0) " (+${it.suppressed})" else ""
                "${format.format(Date(it.atEpochMs))}  ${it.reason} from ${it.remoteAddress}$more"
            }
        }
    }

    // --- small view helpers ------------------------------------------------------------------------------------

    private fun text(value: String) = TextView(this).apply {
        text = value
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun heading(value: String) = text(value).apply {
        textSize = 18f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val TAG_TOKEN_LABEL = "tokenLabel"
    }
}
