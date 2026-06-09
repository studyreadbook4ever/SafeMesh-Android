package com.safemesh.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root composable. Owns state-based navigation (no navigation-compose dep):
 *   screen ∈ { Broadcast, Dm(peerId) }, plus a Material3 ModalNavigationDrawer
 *   for the contacts side sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshApp(
    state: MeshUiState,
    onSendBroadcast: (String) -> Unit,
    onSendDm: (Int, String) -> Unit,
    onResetSystem: () -> Unit,
    onSetNickname: (String) -> Unit
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Broadcast) }
    var resetTapCount by remember { mutableStateOf(0) }
    var lastResetTapMs by remember { mutableStateOf(0L) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showNickDialog by remember { mutableStateOf(false) }
    var nickInput by remember { mutableStateOf("") }
    var nickTapJob by remember { mutableStateOf<Job?>(null) }
    val nickFocusRequester = remember { FocusRequester() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun noteResetTap() {
        val now = System.currentTimeMillis()
        resetTapCount = if (now - lastResetTapMs <= RESET_TAP_WINDOW_MS) resetTapCount + 1 else 1
        lastResetTapMs = now
        if (resetTapCount >= RESET_TAP_COUNT) {
            nickTapJob?.cancel()
            resetTapCount = 0
            showResetDialog = true
        } else {
            nickTapJob?.cancel()
            nickTapJob = scope.launch {
                delay(NICK_EDIT_DELAY_MS)
                if (resetTapCount == 1) {
                    resetTapCount = 0
                    nickInput = state.selfNick
                    showNickDialog = true
                }
            }
        }
    }

    if (showNickDialog) {
        LaunchedEffect(Unit) {
            nickFocusRequester.requestFocus()
        }
        AlertDialog(
            onDismissRequest = { showNickDialog = false },
            title = { Text("Edit nickname") },
            text = {
                OutlinedTextField(
                    value = nickInput,
                    onValueChange = { nickInput = it },
                    singleLine = true,
                    label = { Text("Nickname") },
                    modifier = Modifier.focusRequester(nickFocusRequester)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNickDialog = false
                        onSetNickname(nickInput)
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNickDialog = false }) { Text("Cancel") }
            },
            properties = DialogProperties()
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset system?") },
            text = { Text("This clears identity, sessions, peers, and current messages, then restarts the BLE mesh.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        screen = Screen.Broadcast
                        onResetSystem()
                    }
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ContactsDrawer(
                    state = state,
                    onOpenBroadcast = {
                        screen = Screen.Broadcast
                        scope.launch { drawerState.close() }
                    },
                    onOpenDm = { pid ->
                        screen = Screen.Dm(pid)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                when (val s = screen) {
                    is Screen.Broadcast -> TopAppBar(
                        title = {
                            Column(modifier = Modifier.clickable { noteResetTap() }) {
                                Text(
                                    text = state.selfNick.ifBlank { "MeshTalk" },
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Offline BLE Mesh",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Contacts"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors()
                    )
                    is Screen.Dm -> TopAppBar(
                        title = {
                            Column(modifier = Modifier.clickable { noteResetTap() }) {
                                DmTitle(peerId = s.peerId, state = state)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { screen = Screen.Broadcast }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors()
                    )
                }
            }
        ) { pad ->
            when (val s = screen) {
                is Screen.Broadcast -> HomeScreen(
                    state = state,
                    onSendBroadcast = onSendBroadcast,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad)
                )
                is Screen.Dm -> DmScreen(
                    peerId = s.peerId,
                    state = state,
                    onSendDm = onSendDm,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad)
                )
            }
        }
    }
}

private const val RESET_TAP_COUNT = 3
private const val RESET_TAP_WINDOW_MS = 1_200L
private const val NICK_EDIT_DELAY_MS = 320L
