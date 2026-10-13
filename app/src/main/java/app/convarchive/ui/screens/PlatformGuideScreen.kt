package app.convarchive.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.convarchive.ui.Routes
import app.convarchive.ui.components.ScreenScaffold

private data class PlatformInfo(
    val slug: String,
    val name: String,
    val siteUrl: String,
    val intro: String,
    val can: List<String>,
    val cannot: List<String>,
)

private val geminiInfo = PlatformInfo(
    slug = "gemini",
    name = "Gemini",
    siteUrl = "https://gemini.google.com/",
    intro = "Gemini chats are captured in your own browser with the companion " +
        "userscript, which adds an \"Export to Archive (JSON)\" button to the page. " +
        "You then import that JSON here.",
    can = listOf(
        "Messages you have scrolled into view in the chat UI",
        "Message text as rendered, including headings and lists",
        "Code blocks as shown on the page",
        "Timestamps displayed next to messages",
        "The conversation title",
    ),
    cannot = listOf(
        "Anything behind the login wall: you must be signed in, and the script " +
            "runs inside your own browser session (it never asks for your password)",
        "Messages you never scroll to: only rendered content can be extracted " +
            "(virtual-scroll windows are out of reach)",
        "Collapsed or branched content you did not open",
        "Image/attachment contents: metadata only (file names), never the bytes",
        "Selectors are DOM-dependent: if Google changes the page markup, " +
            "extraction can break. The script logs how many messages it found " +
            "so failures are visible, not silent",
    ),
)

private val aiStudioInfo = PlatformInfo(
    slug = "aistudio",
    name = "Google AI Studio",
    siteUrl = "https://aistudio.google.com/",
    intro = "AI Studio prompts and responses are captured in your own browser " +
        "with the companion userscript, which adds an \"Export to Archive (JSON)\" " +
        "button to the page. You then import that JSON here.",
    can = listOf(
        "Prompts and model responses you have scrolled into view",
        "Message text as rendered, including code blocks",
        "System instructions and run settings when the page shows them",
        "Timestamps displayed next to messages",
    ),
    cannot = listOf(
        "Anything behind the login wall: you must be signed in, and the script " +
            "runs inside your own browser session (it never asks for your password or API key)",
        "Messages you never scroll to: only rendered content can be extracted " +
            "(virtual-scroll windows are out of reach)",
        "API keys, billing details, or anything not shown on the page",
        "Selectors are DOM-dependent: if Google changes the page markup, " +
            "extraction can break. The script logs how many messages it found " +
            "so failures are visible, not silent",
    ),
)

private val julesInfo = PlatformInfo(
    slug = "jules",
    name = "Google Jules",
    siteUrl = "https://jules.google.com/",
    intro = "Jules tasks are archived as task/session records: the prompt, status, " +
        "timestamps, and the activity log. Capture happens in your own browser " +
        "with the companion userscript, which adds an \"Export to Archive (JSON)\" " +
        "button to the page. You then import that JSON here.",
    can = listOf(
        "Task title, task id, prompt, and status as shown on the page",
        "Activity-log entries (status changes, steps, errors) as shown, " +
            "stored as \"event\" messages",
        "Plans and diffs, but only if the page visibly shows them",
        "Timestamps displayed on the page",
    ),
    cannot = listOf(
        "Anything the page does not expose: nothing is invented or reconstructed. " +
            "If a diff or plan is not visible, it is listed as unavailable, never guessed",
        "Repository contents: the script does not browse your code",
        "Anything behind the login wall: you must be signed in, and the script " +
            "runs inside your own browser session (it never asks for your password)",
        "Activity entries you never scroll to: only rendered content can be extracted",
        "Selectors are DOM-dependent: if Google changes the page markup, " +
            "extraction can break. The script logs what it found so failures " +
            "are visible, not silent",
    ),
)

private fun infoFor(slug: String): PlatformInfo = when (slug) {
    "aistudio" -> aiStudioInfo
    "jules" -> julesInfo
    else -> geminiInfo
}

/** Generic, accurate userscript install steps (used when the assets guide is absent). */
private val genericInstallSteps = listOf(
    "Install a userscript manager in an Android browser that supports extensions: " +
        "Tampermonkey (Firefox for Android and other extension-capable browsers) " +
        "or Violentmonkey (Firefox for Android).",
    "Get the companion userscript for this platform from the Conversation Archive " +
        "project (userscripts/ folder). Each script is @match-scoped to its own " +
        "site only.",
    "In the manager's dashboard, create a new script, paste the userscript code, " +
        "and save. Make sure the script is enabled.",
    "Open the site in that same browser and open a conversation or task. The " +
        "script adds an \"Export to Archive (JSON)\" button to the page.",
    "Tap the button and check the script's log line (\"messages found: N\"). " +
        "Save the downloaded JSON file.",
    "Import the JSON here: share the file to Conversation Archive, or use " +
        "Import Conversations on the home screen.",
)

/** Reads app/src/main/assets/userscript-guide.md when another agent ships it. */
private fun readAssetGuide(context: android.content.Context): String? = runCatching {
    context.assets.open("userscript-guide.md").bufferedReader().use { it.readText() }
        .takeIf { it.isNotBlank() }
}.getOrNull()

@Composable
fun PlatformGuideScreen(navController: NavController, platform: String) {
    val info = infoFor(platform)
    val context = LocalContext.current
    var installExpanded by remember { mutableStateOf(false) }
    val assetGuide = remember { readAssetGuide(context) }

    ScreenScaffold(
        title = "${info.name} capture guide",
        navController = navController,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(info.intro, style = MaterialTheme.typography.bodyMedium)

            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.siteUrl))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open ${info.name} in browser")
            }
            Text(
                "Opening the site never captures anything by itself. Capture happens " +
                    "only when you run the companion userscript and import its JSON.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("What CAN be captured", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    info.can.forEach { Bullet(text = it, positive = true) }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("What CANNOT be captured", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    info.cannot.forEach { Bullet(text = it, positive = false) }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "How to install the userscript",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { installExpanded = !installExpanded }) {
                            Icon(
                                if (installExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (installExpanded) "Collapse" else "Expand",
                            )
                        }
                    }
                    if (installExpanded) {
                        Spacer(Modifier.height(8.dp))
                        if (assetGuide != null) {
                            Text(assetGuide, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            genericInstallSteps.forEachIndexed { i, step ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        "${i + 1}. ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(step, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { navController.navigate(Routes.IMPORT) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FileUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import the exported JSON")
            }
        }
    }
}

@Composable
private fun Bullet(text: String, positive: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (positive) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
