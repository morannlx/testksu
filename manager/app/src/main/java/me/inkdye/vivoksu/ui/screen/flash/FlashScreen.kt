package me.inkdye.vivoksu.ui.screen.flash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.inkdye.vivoksu.Natives
import me.inkdye.vivoksu.ui.LocalUiMode
import me.inkdye.vivoksu.ui.UiMode
import me.inkdye.vivoksu.ui.navigation3.LocalNavigator
import me.inkdye.vivoksu.ui.util.reboot

@Composable
fun FlashScreen(flashIt: FlashIt) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val logContent = remember { StringBuilder() }
    var showRebootAction by rememberSaveable { mutableStateOf(false) }
    var flashingStatus by rememberSaveable { mutableStateOf(FlashingStatus.FLASHING) }
    val needJailbreakWarning = flashIt is FlashIt.FlashBoot && Natives.isLateLoadMode
    var flashingEnabled by rememberSaveable { mutableStateOf(!needJailbreakWarning) }

    FlashEffect(
        flashIt = flashIt,
        text = text,
        logContent = logContent,
        onTextUpdate = { text = it },
        onShowRebootChange = { showRebootAction = it },
        onFlashingStatusChange = { flashingStatus = it },
        enabled = flashingEnabled,
    )

    val state = FlashUiState(
        text = text,
        showRebootAction = showRebootAction,
        flashingStatus = flashingStatus,
        showJailbreakWarning = needJailbreakWarning && !flashingEnabled,
    )
    val actions = FlashScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSaveLog = saveLog(logContent, context, scope),
        onReboot = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    reboot()
                }
            }
        },
        onConfirmJailbreakWarning = { flashingEnabled = true },
        onDismissJailbreakWarning = dropUnlessResumed { navigator.pop() },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> FlashScreenMiuix(state, actions)
        UiMode.Material -> FlashScreenMaterial(state, actions)
    }
}
