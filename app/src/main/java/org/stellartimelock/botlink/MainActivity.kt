package org.stellartimelock.botlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.stellartimelock.botlink.flipsend.FlipSendPipe
import org.stellartimelock.botlink.service.LinkService
import org.stellartimelock.botlink.ui.BotLinkRoot
import org.stellartimelock.botlink.ui.BotLinkViewModel
import org.stellartimelock.botlink.ui.theme.BotLinkTheme
import org.stellartimelock.botlink.wallet.WalletSign

class MainActivity : ComponentActivity() {
    private val viewModel: BotLinkViewModel by viewModels { BotLinkViewModel.factory(application) }
    private var pipeQueried = false
    private val pipeQuery = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val extras = result.data?.extras
        viewModel.onPipeResult(
            FlipSendPipe.parsePairedExtra(
                extras?.containsKey(FlipSendPipe.EXTRA_PAIRED) == true,
                extras?.getBoolean(FlipSendPipe.EXTRA_PAIRED) == true,
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotifyPermission()
        setContent {
            BotLinkTheme {
                BotLinkRoot(viewModel = viewModel)
            }
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        acceptSigned(intent)
        acceptShareOrRoom(intent)
    }

    private fun acceptSigned(intent: Intent?) {
        val xdr = WalletSign.xdrFromCallback(intent?.data) ?: return
        viewModel.onSignedXdr(xdr)
    }

    private fun acceptShareOrRoom(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != ACTION_OPEN_AI_ROOM) return
        viewModel.openAiRoom(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    companion object {
        const val ACTION_OPEN_AI_ROOM = "org.stellartimelock.botlink.action.OPEN_AI_ROOM"

        @Volatile
        var isInForeground: Boolean = false
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        val app = application as BotLinkApp
        app.updates.onHostResume()
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshFlipSend()
        if (viewModel.ui.value.flipSendInstalled) {
            LinkService.start(this)
            queryPipeOnce()
        }
    }

    private fun queryPipeOnce() {
        if (pipeQueried) return
        val intent = viewModel.pipeQueryIntent() ?: return
        pipeQueried = true
        try {
            pipeQuery.launch(intent)
        } catch (_: Exception) {
            viewModel.onPipeResult(FlipSendPipe.Status.Unknown)
        }
    }

    private fun requestNotifyPermission() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) return
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }
}
