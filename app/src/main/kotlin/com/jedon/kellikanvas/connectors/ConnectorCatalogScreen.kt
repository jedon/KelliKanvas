package com.jedon.kellikanvas.connectors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.source.connected.PhotoConnector
import com.jedon.kellikanvas.ui.tv.KanvasButton
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold

@Suppress("ktlint:standard:function-naming")
@Composable
fun ConnectorCatalogScreen(onChoose: (PhotoConnector) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    KanvasSetupScaffold("Connect a photo source", "Your cloud storage, photo libraries and media servers, together.", onBack) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val columns = if (maxWidth >= 800.dp) {
                3
            } else if (maxWidth >= 560.dp) {
                2
            } else {
                1
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Find a service") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val providers = PhotoConnector.entries.filter { query.isBlank() || "${it.title} ${it.help} ${it.category}".contains(query, true) }
                if (providers.isEmpty()) Text("No matching service. Try WebDAV for compatible storage.", color = KanvasColors.Muted)
                providers.groupBy { it.category }.forEach { (category, choices) ->
                    Text(category, style = MaterialTheme.typography.titleMedium, color = KanvasColors.Accent)
                    choices.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { provider ->
                                Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(16.dp), color = KanvasColors.Surface, border = BorderStroke(1.dp, KanvasColors.Border)) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(provider.title, style = MaterialTheme.typography.titleMedium)
                                        Text(provider.summary, style = MaterialTheme.typography.bodySmall, color = KanvasColors.Muted)
                                        Spacer(Modifier.weight(1f))
                                        KanvasButton("Connect ${provider.title}", { onChoose(provider) })
                                    }
                                }
                            }
                            repeat(columns - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Text("For Google Drive and Google Photos, use the Google connections in your collection. Local folders, USB, SMB and DLNA are available there too.", style = MaterialTheme.typography.bodySmall, color = KanvasColors.Muted)
            }
        }
    }
}
