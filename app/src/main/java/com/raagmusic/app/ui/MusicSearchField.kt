package com.raagmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** One quiet search surface for online music and personal collections. */
@Composable
internal fun MusicSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    onClear: () -> Unit = { onQueryChange("") },
    leadingIcon: @Composable () -> Unit = { MusicIcon(Icons.Rounded.Search, null, tint = Muted) },
    trailingAction: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = query, onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = leadingIcon,
        trailingIcon = {
            Row {
                if (query.isNotEmpty()) IconButton(onClick = onClear) {
                    MusicIcon(Icons.Rounded.Close, "Clear search", tint = Muted)
                }
                trailingAction?.invoke()
            }
        },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}
