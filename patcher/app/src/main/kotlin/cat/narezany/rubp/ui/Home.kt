package cat.narezany.rubp.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.narezany.rubp.BuildConfig
import cat.narezany.rubp.Strings
import cat.narezany.rubp.data.Installed
import cat.narezany.rubp.data.Prefs
import cat.narezany.rubp.data.Standing
import cat.narezany.rubp.data.Versions
import cat.narezany.rubp.work.Engine

/** What to say under a mod's name. */
fun standingText(words: Strings, standing: Standing): String {
    val installed = standing.installed
    val latest = standing.latest
    return when {
        installed is Installed.Theirs -> words.originalInstalled
        installed is Installed.Nothing -> words.notInstalled
        installed is Installed.Ours && !installed.sure -> words.installedSome
        standing.outdated && latest != null -> words.updateTo(latest.version)
        installed is Installed.Ours && installed.version != null -> words.installed(installed.version)
        else -> words.installedOld
    }
}

/**
 * The one screen that matters: the mod, what is wanted of it, and the work.
 *
 * Nothing starts here on its own. The parameters are set first; the build
 * runs only when the person presses the button for it, down in the work card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(settings: () -> Unit) {
    val words = LocalWords.current
    val context = LocalContext.current
    val catalog by Shelf.catalog.collectAsStateWithLifecycle()
    val loading by Shelf.loading.collectAsStateWithLifecycle()
    val failed by Shelf.failed.collectAsStateWithLifecycle()
    val looked by Shelf.looked.collectAsStateWithLifecycle()
    val prefs by Prefs.of(context).now.collectAsStateWithLifecycle()
    val selfUpdate by Shelf.selfUpdate.collectAsStateWithLifecycle()
    val state by Engine.state.collectAsStateWithLifecycle()
    val behaviour = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var removing by remember { mutableStateOf(false) }

    val mod = catalog?.mods?.firstOrNull()
    val standing = remember(mod, looked, prefs.beta) {
        mod?.let { Standing.of(context, it, prefs.beta) }
    }
    val release = standing?.latest

    // what was made has been installed: nothing more to keep of it
    val mine = (state as? Engine.State.Ready)?.takeIf { it.mod.id == mod?.id }
    LaunchedEffect(looked, mine) {
        val have = (standing?.installed as? Installed.Ours)?.version
        if (mod != null && mine != null && have != null &&
            Versions.compare(have, mine.release.version) >= 0) {
            Engine.done(context)
        }
    }

    fun install(apk: java.io.File) {
        if (mod == null) return
        when {
            standing?.installed is Installed.Theirs -> removing = true
            !Launch.canInstall(context) -> Launch.allowInstalls(context)
            else -> Launch.install(context, apk)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(behaviour.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("RubP", fontWeight = FontWeight.SemiBold) },
                actions = {
                    FilledTonalIconButton(onClick = settings, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Rounded.Settings, contentDescription = words.settings)
                    }
                },
                scrollBehavior = behaviour,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 4.dp,
                bottom = inner.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && catalog == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            val patcher = catalog?.patcher
            if (patcher != null && Versions.compare(patcher.version, BuildConfig.VERSION_NAME) > 0) {
                item {
                    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Roundel(Icons.Rounded.SystemUpdate,
                                    container = MaterialTheme.colorScheme.tertiary,
                                    content = MaterialTheme.colorScheme.onTertiary)
                                Spacer(Modifier.width(16.dp))
                                Text(words.patcherUpdateTitle(patcher.version),
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(words.pick(patcher.notes).ifEmpty { words.patcherUpdateText },
                                style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(16.dp))
                            val progress = selfUpdate
                            if (progress != null) {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            } else {
                                Button(onClick = {
                                    if (Launch.canInstall(context)) Shelf.updateSelf(context, patcher)
                                    else Launch.allowInstalls(context)
                                }) { Text(words.update) }
                            }
                        }
                    }
                }
            }
            if (catalog == null && !loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (failed) words.noNetwork else words.nothingYet,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { Shelf.refresh(context) }) { Text(words.retry) }
                    }
                }
            }
            if (mod != null && standing != null && release != null) {
                // who it is and where it stands
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            ModIcon(mod.icon, 96.dp)
                            if (standing.outdated) {
                                // the dot that says: this one can be updated
                                Box(Modifier.align(Alignment.TopEnd).size(26.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(16.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(mod.name, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(standingText(words, standing), style = MaterialTheme.typography.bodyLarge,
                            color = if (standing.outdated) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(release.version, MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer)
                            if (release.beta) Pill(words.betaTag, MaterialTheme.colorScheme.tertiaryContainer,
                                MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                val about = words.pick(mod.about)
                if (about.isNotEmpty()) {
                    item {
                        Text(about, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    }
                }
                // the parameters first: nothing runs until the button below is pressed
                item { CustomizeCard() }
                // the work, whatever it is at this moment
                item {
                    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        val now = state
                        val kind = when (now) {
                            is Engine.State.Working -> if (now.mod.id == mod.id) "working" else "busy"
                            is Engine.State.Ready -> if (now.mod.id == mod.id) "ready" else "idle"
                            is Engine.State.Failed -> if (now.mod.id == mod.id) "failed" else "idle"
                            else -> "idle"
                        }
                        AnimatedContent(kind, label = "work") { shown ->
                            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                when (shown) {
                                    "working" -> {
                                        val working = now as? Engine.State.Working
                                        if (working != null) {
                                            val stages = Engine.stages(working.release) +
                                                if (prefs.customPackage.isNotBlank() || prefs.customLabel.isNotBlank() ||
                                                    prefs.customIcon.isNotBlank() || prefs.localPatch.isNotBlank() ||
                                                    prefs.lspatch)
                                                    listOf(Engine.Stage.CUSTOMIZE) else emptyList()
                                            val at = stages.indexOf(working.stage)
                                            for ((number, stage) in stages.withIndex()) {
                                                val done = number < at
                                                val current = stage == working.stage
                                                Row(Modifier.padding(vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically) {
                                                    Box(Modifier.size(32.dp).clip(CircleShape).background(
                                                        if (done || current) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.surfaceContainerHighest),
                                                        contentAlignment = Alignment.Center) {
                                                        when {
                                                            done -> Icon(Icons.Rounded.Check, contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                                modifier = Modifier.size(18.dp))
                                                            current -> CircularProgressIndicator(
                                                                color = MaterialTheme.colorScheme.onPrimary,
                                                                strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                                            else -> Text((number + 1).toString(),
                                                                style = MaterialTheme.typography.labelLarge,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                    Spacer(Modifier.width(14.dp))
                                                    Text(words.stage(stage), style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (done || current) MaterialTheme.colorScheme.onSurface
                                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (current) {
                                                    Column(Modifier.padding(start = 46.dp, bottom = 6.dp)) {
                                                        if (working.of > 0) {
                                                            LinearProgressIndicator(
                                                                progress = { (working.done.toFloat() / working.of).coerceIn(0f, 1f) },
                                                                modifier = Modifier.fillMaxWidth())
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(Formatter.formatShortFileSize(context, working.done) + " / " +
                                                                Formatter.formatShortFileSize(context, working.of),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        } else {
                                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(onClick = { Engine.cancel() }) { Text(words.cancel) }
                                        }
                                    }
                                    "ready" -> {
                                        val ready = now as? Engine.State.Ready
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(12.dp))
                                            Text(words.readyTitle, style = MaterialTheme.typography.titleMedium)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(if (!Launch.canInstall(context)) words.allowInstall else words.readyText,
                                            style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.height(16.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { ready?.let { install(it.apk) } }) {
                                                Text(words.installNow)
                                            }
                                            TextButton(onClick = { Engine.cancel() }) { Text(words.later) }
                                        }
                                    }
                                    "failed" -> {
                                        val failedNow = now as? Engine.State.Failed
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error)
                                            Spacer(Modifier.width(12.dp))
                                            Text(when (failedNow?.trouble) {
                                                Engine.Trouble.NETWORK -> words.troubleNetwork
                                                Engine.Trouble.WRONG_APK -> words.troubleWrongApk
                                                Engine.Trouble.BAD_PATCH -> words.troubleBadPatch
                                                Engine.Trouble.NO_ROOM -> words.troubleNoRoom
                                                else -> words.troubleOther
                                            }, style = MaterialTheme.typography.bodyLarge)
                                        }
                                        if (failedNow != null && failedNow.detail.isNotEmpty()
                                                && failedNow.trouble != Engine.Trouble.WRONG_APK) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(failedNow.detail, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = { Engine.cancel(); Engine.start(context, mod, release) }) {
                                            Text(words.retry)
                                        }
                                    }
                                    else -> {
                                        val installed = standing.installed
                                        val fresh = installed is Installed.Ours && !standing.outdated
                                        val label = when {
                                            installed is Installed.Ours && standing.outdated -> words.update
                                            fresh -> words.reinstall
                                            else -> words.install
                                        }
                                        if (fresh) {
                                            Button(onClick = { Launch.open(context, mod.packageName) },
                                                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                                Text(words.open, style = MaterialTheme.typography.titleMedium)
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            FilledTonalButton(enabled = shown != "busy",
                                                onClick = { Engine.start(context, mod, release) },
                                                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                                Text(label, style = MaterialTheme.typography.titleMedium)
                                            }
                                        } else {
                                            Button(enabled = shown != "busy",
                                                onClick = { Engine.start(context, mod, release) },
                                                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                                Text(label, style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Group {
                        if (!release.whole) {
                            row(Icons.Rounded.Android, words.original, release.baseName)
                            row(Icons.Rounded.Healing, words.patch,
                                Formatter.formatShortFileSize(context, release.patchSize))
                        }
                        row(Icons.Rounded.Inventory2, words.finishedApk,
                            Formatter.formatShortFileSize(context, release.resultSize))
                    }
                }
                val notes = words.pick(release.notes)
                if (notes.isNotEmpty()) {
                    item {
                        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                Text(words.whatsNew, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                Text(notes, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                item {
                    Card(
                        onClick = { Launch.link(context, catalog?.channel ?: "") },
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Roundel(Icons.AutoMirrored.Rounded.Send,
                                container = MaterialTheme.colorScheme.secondary,
                                content = MaterialTheme.colorScheme.onSecondary, size = 48.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(words.channelTitle, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text(words.channelText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    // where the original is to come from
    val asking = (state as? Engine.State.Asking)?.takeIf { it.mod.id == mod?.id }
    if (asking != null) {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
            if (chosen != null) Engine.pick(context, asking.mod, asking.release, chosen)
        }
        ModalBottomSheet(onDismissRequest = { Engine.cancel() }) {
            Column(Modifier.padding(bottom = 24.dp).navigationBarsPadding()) {
                Text(words.needOriginalTitle(asking.release.baseName),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                Text(words.needOriginalText, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(12.dp))
                val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
                for (source in asking.release.sources) {
                    ListItem(
                        colors = clear,
                        headlineContent = {
                            Text(if (source.direct) words.downloadFrom(source.name) else words.openPage(source.name))
                        },
                        supportingContent = if (source.direct) {
                            { Text(Formatter.formatShortFileSize(context, asking.release.baseSize)) }
                        } else null,
                        leadingContent = {
                            Icon(if (source.direct) Icons.Rounded.CloudDownload
                            else Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        },
                        modifier = Modifier.clickableRow {
                            if (source.direct) Engine.fetch(context, asking.mod, asking.release, source)
                            else Launch.link(context, source.url)
                        },
                    )
                }
                ListItem(
                    colors = clear,
                    headlineContent = { Text(words.pickFile) },
                    supportingContent = { Text(words.pickFileNote) },
                    leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                    modifier = Modifier.clickableRow {
                        picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                    },
                )
            }
        }
    }

    if (removing && mod != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(words.removeOriginalTitle) },
            text = { Text(words.removeOriginalText) },
            confirmButton = {
                FilledTonalButton(onClick = { removing = false; Launch.remove(context, mod.packageName) }) {
                    Text(words.remove)
                }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(words.cancel) } },
        )
    }
}
