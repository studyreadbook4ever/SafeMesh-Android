package com.safemesh.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.safemesh.android.ble.MeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Discord/bitchat-toned multi-screen UI driven entirely by the service's
 * StateFlow. No polling: onServiceConnected swaps in the live flow and Compose
 * recomposes on every mesh event / send.
 */
class MainActivity : ComponentActivity() {

    private var service: MeshService? = null

    // The live state source. Starts as an empty placeholder flow and is
    // replaced (reference-swapped) with the service's flow on bind, which
    // triggers recomposition because it is read via a Compose State.
    private val stateFlowHolder =
        mutableStateOf<StateFlow<MeshUiState>>(MutableStateFlow(MeshUiState.EMPTY))

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? MeshService.LocalBinder)?.service() ?: return
            service = s
            stateFlowHolder.value = s.state
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> startAndBind() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                AppRoot()
            }
        }
        requestRuntimePermissions()
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(conn) } catch (_: Throwable) {}
    }

    @Composable
    private fun AppRoot() {
        val flow by stateFlowHolder
        val state by flow.collectAsState()
        MeshApp(
            state = state,
            onSendBroadcast = { text -> service?.broadcast(text) },
            onSendDm = { pid, text -> service?.sendDm(pid, text) },
            onResetSystem = { service?.resetSystem() },
            onSetNickname = { nick -> service?.setNickname(nick) }
        )
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(needed.toTypedArray())
    }

    private fun startAndBind() {
        val intent = Intent(this, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private companion object {
        /** bitchat/Discord-ish dark palette. */
        val DarkColors = darkColorScheme(
            primary = Color(0xFF5865F2),          // Discord blurple (my bubbles)
            onPrimary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFF2B2D31), // fingerprint chip / banner
            onSecondaryContainer = Color(0xFFB5BAC1),
            tertiaryContainer = Color(0xFF248046), // broadcast "#" avatar
            onTertiaryContainer = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF3F4248),  // DM avatars
            onPrimaryContainer = Color(0xFFFFFFFF),
            background = Color(0xFF1E1F22),
            onBackground = Color(0xFFDBDEE1),
            surface = Color(0xFF313338),
            onSurface = Color(0xFFF2F3F5),
            surfaceVariant = Color(0xFF383A40),   // others' bubbles
            onSurfaceVariant = Color(0xFFB5BAC1)
        )
    }
}
