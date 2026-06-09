package me.inkdye.vivoksu.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import me.inkdye.vivoksu.ui.LocalUiMode
import me.inkdye.vivoksu.ui.UiMode

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
