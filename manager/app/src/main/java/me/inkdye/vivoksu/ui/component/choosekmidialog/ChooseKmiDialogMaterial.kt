package me.inkdye.vivoksu.ui.component.choosekmidialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.inkdye.vivoksu.R
import me.inkdye.vivoksu.ui.component.material.SegmentedColumn
import me.inkdye.vivoksu.ui.component.material.SegmentedRadioItem
import me.inkdye.vivoksu.ui.util.getCurrentKmi
import me.inkdye.vivoksu.ui.util.getSupportedKmis

@Composable
fun ChooseKmiDialogMaterial(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit,
    preferredKmi: String? = null
) {
    if (!show) return

    val supportedKMIs by produceState(initialValue = emptyList()) {
        value = getSupportedKmis()
    }

    val currentKmi by produceState(initialValue = "") {
        value = getCurrentKmi()
    }

    val orderedKMIs = remember(supportedKMIs, preferredKmi) {
        if (preferredKmi.isNullOrBlank()) {
            supportedKMIs
        } else {
            val preferred = supportedKMIs.firstOrNull { it == preferredKmi }
            buildList {
                preferred?.let { add(it) }
                addAll(supportedKMIs.filter { it != preferred })
            }
        }
    }

    val selectedKmi = remember(orderedKMIs, preferredKmi) {
        mutableStateOf(
            orderedKMIs.firstOrNull { it == preferredKmi }
                ?: orderedKMIs.firstOrNull()
                ?: currentKmi
        )
    }

    AlertDialog(
        onDismissRequest = {
            onDismissRequest()
            selectedKmi.value = currentKmi
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelected(selectedKmi.value)
                    onDismissRequest()
                },
                enabled = supportedKMIs.contains(selectedKmi.value)
            ) {
                Text(stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismissRequest()
                selectedKmi.value = currentKmi
            }) {
                Text(stringResource(id = android.R.string.cancel))
            }
        },
        title = {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                text = stringResource(R.string.select_kmi),
                textAlign = TextAlign.Center
            )
        },
        text = {
            SegmentedColumn(
                content = orderedKMIs.map { kmi ->
                    {
                        SegmentedRadioItem(
                            title = kmi,
                            summary = if (kmi == currentKmi) stringResource(R.string.current_device_kmi) else null,
                            selected = selectedKmi.value == kmi,
                            onClick = { selectedKmi.value = kmi }
                        )
                    }
                }
            )
        }
    )
}
