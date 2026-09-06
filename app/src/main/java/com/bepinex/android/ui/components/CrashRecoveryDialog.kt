package com.bepinex.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bepinex.android.R

@Composable
fun CrashRecoveryDialog(
    packageName: String,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_detected_title)) },
        text = { Text(stringResource(R.string.crash_detected_message, packageName)) },
        confirmButton = {
            TextButton(onClick = onExport) {
                Text(stringResource(R.string.crash_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
