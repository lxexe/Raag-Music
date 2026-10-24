package com.raagmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raagmusic.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private data class LegalDocument(val title: String, val asset: String)
private val legalDocuments = listOf(
    LegalDocument("Raag Music license — GPL v3", "licenses/GPL-3.0.txt"),
    LegalDocument("Third-party notices", "licenses/THIRD_PARTY_NOTICES.txt"),
    LegalDocument("Privacy", "licenses/PRIVACY.txt"),
    LegalDocument("Apache License 2.0", "licenses/Apache-2.0.txt"),
    LegalDocument("Mozilla Public License 2.0", "licenses/MPL-2.0.txt"),
    LegalDocument("jsoup — MIT license", "licenses/jsoup-MIT.txt"),
    LegalDocument("Protocol Buffers — BSD license", "licenses/protobuf-BSD.txt"),
    LegalDocument("JSR-305 — attribution notice", "licenses/jsr305-NOTICE.txt"),
    LegalDocument("Creative Commons Attribution 2.5", "licenses/CC-BY-2.5.txt"),
    LegalDocument("nanojson — attribution notice", "licenses/nanojson-NOTICE.txt"),
    LegalDocument("Desugaring — GPL v2 and Classpath Exception", "licenses/desugar-GPL-2.0-with-classpath-exception.txt"),
    LegalDocument("OpenJDK Assembly Exception", "licenses/desugar-ASSEMBLY_EXCEPTION.txt"),
    LegalDocument("OpenJDK — additional license information", "licenses/desugar-ADDITIONAL_LICENSE_INFO.txt"),
    LegalDocument("Plus Jakarta Sans — font license", "fonts/PlusJakartaSans-OFL.txt"),
)

/** Bundled text remains available offline and never opens an external web page. */
@Composable
internal fun LegalInformation(onClose: () -> Unit) {
    var selected by remember { mutableStateOf<LegalDocument?>(null) }
    val context = LocalContext.current
    val text by produceState<String?>(null, selected) {
        value = null
        selected?.let { document ->
            value = withContext(Dispatchers.IO) {
                try { context.assets.open(document.asset).bufferedReader().use { it.readText() } }
                catch (_: IOException) { "This document could not be opened. Please restart the app and try again." }
            }
        }
    }
    val close = { if (selected != null) selected = null else onClose() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                SettingsHeader(selected?.title ?: "About Raag Music", close)
                if (selected == null) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("Raag Music ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                            Text("Copyright 2026 Lx and Raag Music contributors.", style = MaterialTheme.typography.bodyMedium)
                            Text("Free software under GPL-3.0-or-later. You may use, share and modify it under that license. Distributed without warranty.",
                                modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        itemsIndexed(legalDocuments, key = { _, document -> document.asset }) { _, document ->
                            SettingsAction(document.title) { selected = document }
                        }
                    }
                } else {
                    val paragraphs = remember(text) { text?.split(Regex("\\n\\s*\\n")) ?: emptyList() }
                    if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    key(selected) {
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(paragraphs) { _, paragraph ->
                                SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
}
