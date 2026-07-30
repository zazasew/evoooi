package com.morningdigest.app.ui.assistants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.facts.AssistantDetailLine
import com.morningdigest.app.data.facts.AssistantReportBuilder
import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.prefs.MascotCharacter
import com.morningdigest.app.data.remote.PoliceIncidentFetcher
import com.morningdigest.app.ui.mascot.AnimatedMascotIllustration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dedicated per-character report screen - a fuller version of that
 * analyst's briefing (multiple headlines/movers, not just one line), with
 * its own refresh button. Still no chat, no interaction beyond refresh.
 *
 * Max (police) is special-cased throughout: his "report" is the live public
 * police incident feed rather than a DigestReport-derived summary, so this
 * screen loads/refreshes it directly from [PoliceIncidentFetcher] and renders
 * a dedicated list (up to 20 incidents, newest first, full text + time/date)
 * instead of the generic [AssistantDetailLine] rows the other analysts use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantDetailScreen(characterId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MorningDigestApp).container
    val character = MascotCharacter.fromId(characterId) ?: MascotCharacter.PANDA
    val isPolice = character == MascotCharacter.MAX

    var report by remember { mutableStateOf<DigestReport?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Max-only state: his own live incident list, the configured municipality
    // (for the "<Municipality> Police Report" title), and when it was last fetched.
    var policeIncidents by remember { mutableStateOf<List<PoliceIncidentFetcher.Incident>>(emptyList()) }
    var municipalities by remember { mutableStateOf<List<String>>(emptyList()) }
    var policeUpdatedMillis by remember { mutableStateOf<Long?>(null) }

    suspend fun loadPoliceIncidents() {
        val settings = container.settingsRepository.currentSettings()
        municipalities = settings.policeMunicipalities.ifEmpty { listOf(settings.policeMunicipality) }
        val dismissed = container.settingsRepository.getPoliceDismissedIds()
        policeIncidents = runCatching {
            container.policeIncidentFetcher.fetch(
                municipalities,
                settings.policeCategories,
                100
            )
                .filter { it.id !in dismissed }
                .sortedByDescending { it.createdMillis }
                .take(20)
        }.getOrDefault(emptyList())
        policeUpdatedMillis = System.currentTimeMillis()
    }

    LaunchedEffect(characterId) {
        if (isPolice) loadPoliceIncidents() else report = container.digestRepository.getLatestReport()
    }

    fun refresh() {
        scope.launch {
            isRefreshing = true
            if (isPolice) {
                runCatching { loadPoliceIncidents() }
            } else {
                runCatching {
                    val settings = container.settingsRepository.currentSettings()
                    when (character) {
                        MascotCharacter.PANDA -> container.digestRepository.refreshBusinessSection(settings)
                        MascotCharacter.OWL -> container.digestRepository.refreshWorldNewsSection(settings)
                        MascotCharacter.BULL, MascotCharacter.BEAR, MascotCharacter.FOX ->
                            container.digestRepository.refreshMarketsSection(settings)
                        MascotCharacter.CAT -> container.digestRepository.refreshPoliticsSection(settings)
                        MascotCharacter.MAX -> null // unreachable, isPolice branch handles Max
                    }
                }.getOrNull()?.let { report = it }
            }
            isRefreshing = false
        }
    }

    val accent = Color(character.accentColorArgb)
    val lines = remember(report, character) {
        if (isPolice) emptyList() else AssistantReportBuilder.buildDetailLines(character, report)
    }
    val uriHandler = LocalUriHandler.current
    val headerTitle = if (isPolice) "Police Reports" else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(character.displayName, style = MaterialTheme.typography.titleMedium, color = accent)
                        Text(character.role, style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                    } else {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent.copy(alpha = 0.22f), MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedMascotIllustration(character, modifier = Modifier.size(60.dp), size = 60.dp)
                    }
                    Spacer(Modifier.size(14.dp))
                    Column {
                        if (isPolice) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🛡️", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.size(6.dp))
                                Text(headerTitle ?: "Police Report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
                            }
                            Spacer(Modifier.size(4.dp))
                            Text(character.description, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(character.description, style = MaterialTheme.typography.bodyMedium)
                        }
                        val updatedMillis = if (isPolice) policeUpdatedMillis else report?.timestampMillis
                        updatedMillis?.let {
                            Spacer(Modifier.size(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(accent.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                            }
                        }
                    }
                }
            }
            if (isPolice) {
                if (policeIncidents.isEmpty()) {
                    item {
                        Text(
                            if (municipalities.isNotEmpty()) "No public police incidents right now for ${municipalities.joinToString(", ")}."
                            else "No public police incidents right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                val groupedByMunicipality = policeIncidents
                    .groupBy { it.municipality.ifBlank { "Unknown municipality" } }
                    .toList()
                    .sortedBy { entry -> entry.first.lowercase(Locale.ENGLISH) }

                groupedByMunicipality.forEachIndexed { index, (municipalityName, incidents) ->
                    item(key = "municipality_header_$municipalityName") {
                        PoliceMunicipalityHeader(
                            municipalityName = municipalityName,
                            color = municipalityHeaderColors[index % municipalityHeaderColors.size]
                        )
                    }
                    items(
                        items = incidents,
                        key = { it.id }
                    ) { incident ->
                        PoliceIncidentDetailRow(incident, accent, uriHandler)
                    }
                }
                if (policeIncidents.isNotEmpty()) {
                    item {
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://www.politiet.no/politiloggen") } },
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Text("Source: Norwegian Police · Politiloggen", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                items(lines) { line -> AssistantDetailLineRow(line, uriHandler) }
            }
        }
    }
}

@Composable
private fun AssistantDetailLineRow(line: AssistantDetailLine, uriHandler: androidx.compose.ui.platform.UriHandler) {
    val isLink = line.link != null
    val moveTint = when {
        line.text.contains("▲") -> Color(0xFF2ECC71)
        line.text.contains("▼") -> Color(0xFFE53935)
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (isLink) Modifier.clickable {
                    runCatching { uriHandler.openUri(line.link!!) }
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = moveTint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isLink) {
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = "Open article",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One row in Max's full report - unlike the other analysts' single-line
 * rows, this shows the full (untruncated) translated text, the category and
 * area, and a formatted date + time, plus a link out to the official source.
 */
private val municipalityHeaderColors = listOf(
    Color(0xFF4FC3F7),
    Color(0xFFFFB74D),
    Color(0xFF81C784),
    Color(0xFFBA68C8),
    Color(0xFFE57373),
    Color(0xFF4DB6AC)
)

@Composable
private fun PoliceMunicipalityHeader(
    municipalityName: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 5.dp, height = 24.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "$municipalityName Police Reports",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun PoliceIncidentDetailRow(
    incident: PoliceIncidentFetcher.Incident,
    accent: Color,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { runCatching { uriHandler.openUri(incident.sourceUrl) } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${incident.categoryEn}${incident.area.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier
                    .weight(1f)
                    .clickable { runCatching { uriHandler.openUri(incident.sourceUrl) } }
            )
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = "Open source",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(incident.englishText, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${formatFullIncidentTime(incident.createdMillis)} · ${incident.municipality}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatFullIncidentTime(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.ENGLISH).format(Date(millis))
