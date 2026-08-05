package dev.iskyd.dok2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Edits a track's name and optional description. Shared by the Live screen (pre-fill on stop) and
 * the Library screen (finished tracks). Local state is seeded from [initialName]/[initialNotes];
 * because the dialog leaves composition between opens the fields always reset to the current
 * values.
 *
 * When [onDiscard] is provided, a destructive "Don't save" button appears next to Cancel: the Live
 * screen uses it to delete the recording instead of finalising it.
 */
@Composable
fun TrackDetailsDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialNotes: String,
    onConfirm: (name: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
    onDiscard: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), notes.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = {
            if (onDiscard != null) {
                TextButton(
                    onClick = onDiscard,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text("Don't save")
                }
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
