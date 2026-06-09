package me.inkdye.vivoksu.ui.component.choosekmidialog

import androidx.compose.runtime.Composable
import me.inkdye.vivoksu.ui.LocalUiMode
import me.inkdye.vivoksu.ui.UiMode

@Composable
fun ChooseKmiDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit,
    preferredKmi: String? = null
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> ChooseKmiDialogMiuix(show, onDismissRequest, onSelected, preferredKmi)
        UiMode.Material -> ChooseKmiDialogMaterial(show, onDismissRequest, onSelected, preferredKmi)
    }
}
