package com.raagmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun ListenerNameDialog(initial: String, firstTime: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (firstTime) "Make yourself at home" else "What should we call you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Add your name for a personal hello. You can change it anytime in Settings.", color = Muted)
                OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(),
                    label = { Text("Your name (optional)") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(name) }))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(if (firstTime) "Let's go" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (firstTime) "Skip for now" else "Cancel") } })
}
