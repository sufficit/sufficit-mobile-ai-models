package com.sufficit.ai.mobiledevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PendingDelete(val kind: ModelKind, val file: File)

/**
 * Model manager (Fase 6, + Whisper support): search Hugging Face for GGUF embedding models,
 * download several, pick which one is "active" (the one [ModelRuntimeService] serves over the
 * tailnet), and smoke-test any downloaded one individually. Also manages whisper.cpp
 * transcription models the same way, from a fixed recommended list (no HF search — whisper.cpp
 * ships all its official models from one repo, not scattered across dozens like embeddings).
 *
 * All of this is a thin remote control: every real action (download, switch, test, delete)
 * is a command sent to [ModelRuntimeService] — running in its own process — not something
 * this screen does inline. That's deliberate ("a execução/download de um modelo não pode
 * derrubar o serviço de api, devem ser processos diferentes"): a crash mid-download or while
 * loading a model must stay contained to that one process, never take the UI or the
 * self-announce/tailnet heartbeat down with it. Results come back as broadcasts.
 */
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val search = remember { HuggingFaceModelSearch() }
    val registry = remember { ModelRegistry(context) }

    var query by rememberSaveable { mutableStateOf("embedding gguf") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<HFModelSummary>>(emptyList()) }
    var searchError by rememberSaveable { mutableStateOf<String?>(null) }

    var expandedRepo by rememberSaveable { mutableStateOf<String?>(null) }
    var repoFiles by remember { mutableStateOf<List<HFModelFile>>(emptyList()) }
    var loadingFiles by remember { mutableStateOf(false) }

    var downloadingFile by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadProgress by rememberSaveable { mutableStateOf(0f) }
    var actionError by rememberSaveable { mutableStateOf<String?>(null) }

    var installedEmbeddingModels by remember { mutableStateOf(registry.installedModels(context, ModelKind.EMBEDDING)) }
    var installedTranscriptionModels by remember { mutableStateOf(registry.installedModels(context, ModelKind.TRANSCRIPTION)) }
    var activeEmbeddingModelName by rememberSaveable { mutableStateOf(registry.activeModelFileName(ModelKind.EMBEDDING)) }
    var activeTranscriptionModelName by rememberSaveable { mutableStateOf(registry.activeModelFileName(ModelKind.TRANSCRIPTION)) }
    // Whether each kind's engine is the one actually resident right now (mutual exclusion,
    // see ModelRuntimeService kdoc) — distinct from "isActive" (registry's chosen file for the
    // kind), which stays true even while the OTHER kind is resident and this one is stopped.
    // Without this, "Usar este modelo" hides forever once a model is registry-active, with no
    // way left to re-promote its kind to resident if the other kind is currently running.
    var embeddingRunning by rememberSaveable { mutableStateOf(false) }
    var transcriptionRunning by rememberSaveable { mutableStateOf(false) }
    var testingFile by rememberSaveable { mutableStateOf<String?>(null) }
    var testingApiFile by rememberSaveable { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<Map<String, EmbeddingTestResult>>(emptyMap()) }
    var transcriptionTestResults by remember { mutableStateOf<Map<String, TranscriptionTestResult>>(emptyMap()) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    fun refreshInstalled() {
        installedEmbeddingModels = registry.installedModels(context, ModelKind.EMBEDDING)
        installedTranscriptionModels = registry.installedModels(context, ModelKind.TRANSCRIPTION)
        activeEmbeddingModelName = registry.activeModelFileName(ModelKind.EMBEDDING)
        activeTranscriptionModelName = registry.activeModelFileName(ModelKind.TRANSCRIPTION)
    }

    // Listens for ModelRuntimeService's outcomes — it runs in a different process, so this is
    // the only way this screen finds out what happened to a command it sent.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ModelRuntimeService.ACTION_STATUS_CHANGED -> {
                        activeEmbeddingModelName = intent.getStringExtra(ModelRuntimeService.EXTRA_ACTIVE_MODEL)
                        activeTranscriptionModelName = intent.getStringExtra(ModelRuntimeService.EXTRA_ACTIVE_TRANSCRIPTION_MODEL)
                        embeddingRunning = intent.getBooleanExtra(ModelRuntimeService.EXTRA_RUNNING, false)
                        transcriptionRunning = intent.getBooleanExtra(ModelRuntimeService.EXTRA_TRANSCRIPTION_RUNNING, false)
                        installedEmbeddingModels = registry.installedModels(context, ModelKind.EMBEDDING)
                        installedTranscriptionModels = registry.installedModels(context, ModelKind.TRANSCRIPTION)
                    }
                    ModelRuntimeService.ACTION_DOWNLOAD_PROGRESS -> {
                        downloadingFile = intent.getStringExtra(ModelRuntimeService.EXTRA_FILE_NAME)
                        downloadProgress = intent.getFloatExtra(ModelRuntimeService.EXTRA_PROGRESS, 0f)
                    }
                    ModelRuntimeService.ACTION_DOWNLOAD_RESULT -> {
                        downloadingFile = null
                        val success = intent.getBooleanExtra(ModelRuntimeService.EXTRA_SUCCESS, false)
                        if (success) {
                            refreshInstalled()
                        } else {
                            actionError = context.getString(R.string.models_download_failed, intent.getStringExtra(ModelRuntimeService.EXTRA_ERROR))
                        }
                    }
                    ModelRuntimeService.ACTION_TEST_RESULT -> {
                        val fileName = intent.getStringExtra(ModelRuntimeService.EXTRA_FILE_NAME) ?: return
                        val kind = intent.getStringExtra(ModelRuntimeService.EXTRA_MODEL_KIND)
                            ?.let { runCatching { ModelKind.valueOf(it) }.getOrNull() } ?: ModelKind.EMBEDDING
                        testingFile = null
                        testingApiFile = null
                        val success = intent.getBooleanExtra(ModelRuntimeService.EXTRA_SUCCESS, false)
                        when (kind) {
                            ModelKind.EMBEDDING -> testResults = testResults + (fileName to if (success) {
                                EmbeddingTestResult.Success(
                                    dimensions = intent.getIntExtra(ModelRuntimeService.EXTRA_DIMENSIONS, 0),
                                    latencyMs = intent.getLongExtra(ModelRuntimeService.EXTRA_LATENCY_MS, 0)
                                )
                            } else {
                                EmbeddingTestResult.Failure(intent.getStringExtra(ModelRuntimeService.EXTRA_ERROR) ?: context.getString(R.string.models_unknown_error))
                            })
                            ModelKind.TRANSCRIPTION -> transcriptionTestResults = transcriptionTestResults + (fileName to if (success) {
                                TranscriptionTestResult.Success(
                                    text = intent.getStringExtra(ModelRuntimeService.EXTRA_TRANSCRIPTION_TEXT) ?: "",
                                    latencyMs = intent.getLongExtra(ModelRuntimeService.EXTRA_LATENCY_MS, 0)
                                )
                            } else {
                                TranscriptionTestResult.Failure(intent.getStringExtra(ModelRuntimeService.EXTRA_ERROR) ?: context.getString(R.string.models_unknown_error))
                            })
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ModelRuntimeService.ACTION_STATUS_CHANGED)
            addAction(ModelRuntimeService.ACTION_DOWNLOAD_PROGRESS)
            addAction(ModelRuntimeService.ACTION_DOWNLOAD_RESULT)
            addAction(ModelRuntimeService.ACTION_TEST_RESULT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ModelRuntimeService.queryStatus(context)
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun runSearch() {
        if (query.isBlank()) return
        scope.launch {
            searching = true
            searchError = null
            expandedRepo = null
            try {
                searchResults = withContext(Dispatchers.IO) { search.search(query) }
            } catch (ex: Exception) {
                searchError = ex.message ?: context.getString(R.string.models_search_failed)
            } finally {
                searching = false
            }
        }
    }

    fun toggleRepo(repoId: String) {
        if (expandedRepo == repoId) {
            expandedRepo = null
            return
        }
        expandedRepo = repoId
        scope.launch {
            loadingFiles = true
            repoFiles = try {
                withContext(Dispatchers.IO) { search.listGgufFiles(repoId) }
            } catch (ex: Exception) {
                actionError = ex.message ?: context.getString(R.string.models_list_files_failed)
                emptyList()
            } finally {
                loadingFiles = false
            }
        }
    }

    fun downloadTo(fileName: String, url: String) {
        downloadingFile = fileName
        downloadProgress = 0f
        actionError = null
        ModelRuntimeService.download(context, fileName, url)
    }

    fun setActive(kind: ModelKind, file: File) {
        actionError = null
        ModelRuntimeService.switchTo(context, kind, file.name)
    }

    fun testModel(kind: ModelKind, file: File) {
        testingFile = file.name
        actionError = null
        ModelRuntimeService.test(context, kind, file.name)
    }

    fun testModelApi(kind: ModelKind, file: File) {
        testingApiFile = file.name
        actionError = null
        ModelRuntimeService.testApi(context, kind, file.name)
    }

    fun deleteModel(kind: ModelKind, file: File) {
        when (kind) {
            ModelKind.EMBEDDING -> testResults = testResults - file.name
            ModelKind.TRANSCRIPTION -> transcriptionTestResults = transcriptionTestResults - file.name
        }
        ModelRuntimeService.delete(context, kind, file.name)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineSmall)
            }
        }

        actionError?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.models_recommended_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.models_recommended_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(DeviceModelCatalog.recommended(ModelKind.EMBEDDING)) { known ->
            RecommendedModelCard(
                known = known,
                alreadyInstalled = installedEmbeddingModels.any { it.name == known.fileName },
                downloadingFile = downloadingFile,
                downloadProgress = downloadProgress,
                onDownload = { downloadTo(known.fileName, known.downloadUrl) }
            )
        }

        item { HorizontalDivider() }

        item { Text(stringResource(R.string.models_installed_title), style = MaterialTheme.typography.titleSmall) }
        if (installedEmbeddingModels.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.models_none_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(installedEmbeddingModels, key = { it.name }) { file ->
            InstalledEmbeddingModelRow(
                file = file,
                isActive = file.name == activeEmbeddingModelName,
                isResident = file.name == activeEmbeddingModelName && embeddingRunning,
                testing = testingFile == file.name,
                testingApi = testingApiFile == file.name,
                testResult = testResults[file.name],
                onSetActive = { setActive(ModelKind.EMBEDDING, file) },
                onTest = { testModel(ModelKind.EMBEDDING, file) },
                onTestApi = { testModelApi(ModelKind.EMBEDDING, file) },
                onDelete = { pendingDelete = PendingDelete(ModelKind.EMBEDDING, file) }
            )
        }

        item { HorizontalDivider() }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.models_transcription_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.models_transcription_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(DeviceModelCatalog.recommended(ModelKind.TRANSCRIPTION)) { known ->
            RecommendedModelCard(
                known = known,
                alreadyInstalled = installedTranscriptionModels.any { it.name == known.fileName },
                downloadingFile = downloadingFile,
                downloadProgress = downloadProgress,
                onDownload = { downloadTo(known.fileName, known.downloadUrl) }
            )
        }

        if (installedTranscriptionModels.isNotEmpty()) {
            items(installedTranscriptionModels, key = { it.name }) { file ->
                InstalledTranscriptionModelRow(
                    file = file,
                    isActive = file.name == activeTranscriptionModelName,
                    isResident = file.name == activeTranscriptionModelName && transcriptionRunning,
                    testing = testingFile == file.name,
                    testingApi = testingApiFile == file.name,
                    testResult = transcriptionTestResults[file.name],
                    onSetActive = { setActive(ModelKind.TRANSCRIPTION, file) },
                    onTest = { testModel(ModelKind.TRANSCRIPTION, file) },
                    onTestApi = { testModelApi(ModelKind.TRANSCRIPTION, file) },
                    onDelete = { pendingDelete = PendingDelete(ModelKind.TRANSCRIPTION, file) }
                )
            }
        }

        item { HorizontalDivider() }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.models_search_title), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.models_search_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(onClick = { runSearch() }, enabled = !searching) {
                        if (searching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                }
            }
        }

        searchError?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        items(searchResults, key = { it.repoId }) { summary ->
            SearchResultCard(
                summary = summary,
                expanded = expandedRepo == summary.repoId,
                files = if (expandedRepo == summary.repoId) repoFiles else emptyList(),
                loadingFiles = expandedRepo == summary.repoId && loadingFiles,
                downloadingFile = downloadingFile,
                downloadProgress = downloadProgress,
                onToggle = { toggleRepo(summary.repoId) },
                onDownload = { downloadTo(it.fileName, it.downloadUrl) }
            )
        }
    }

    pendingDelete?.let { pending ->
        val isActive = when (pending.kind) {
            ModelKind.EMBEDDING -> pending.file.name == activeEmbeddingModelName
            ModelKind.TRANSCRIPTION -> pending.file.name == activeTranscriptionModelName
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.models_delete_confirm_title)) },
            text = {
                Text(
                    if (isActive) stringResource(R.string.models_delete_confirm_body_active, pending.file.name)
                    else stringResource(R.string.models_delete_confirm_body, pending.file.name)
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteModel(pending.kind, pending.file); pendingDelete = null }) {
                    Text(stringResource(R.string.models_delete_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.models_delete_cancel_action)) }
            }
        )
    }
}

@Composable
private fun RecommendedModelCard(
    known: KnownGoodModel,
    alreadyInstalled: Boolean,
    downloadingFile: String?,
    downloadProgress: Float,
    onDownload: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(known.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (known.dimensions != null) {
                        stringResource(R.string.models_recommended_specs, known.dimensions, known.sizeGB.toString())
                    } else {
                        stringResource(R.string.models_size_only_spec, known.sizeGB.toString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                known.details?.let { details ->
                    Text(
                        details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                alreadyInstalled -> Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.models_already_installed), tint = MaterialTheme.colorScheme.primary)
                downloadingFile == known.fileName -> Column(horizontalAlignment = Alignment.End) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(width = 80.dp, height = 4.dp)
                    )
                    Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                else -> Button(onClick = onDownload, enabled = downloadingFile == null) {
                    Text(stringResource(R.string.models_download))
                }
            }
        }
    }
}

@Composable
private fun InstalledEmbeddingModelRow(
    file: File,
    isActive: Boolean,
    isResident: Boolean,
    testing: Boolean,
    testingApi: Boolean,
    testResult: EmbeddingTestResult?,
    onSetActive: () -> Unit,
    onTest: () -> Unit,
    onTestApi: () -> Unit,
    onDelete: () -> Unit
) {
    InstalledModelRowShell(
        file = file,
        isActive = isActive,
        isResident = isResident,
        testing = testing,
        testingApi = testingApi,
        onSetActive = onSetActive,
        onTest = onTest,
        onTestApi = onTestApi,
        onDelete = onDelete
    ) {
        when (testResult) {
            is EmbeddingTestResult.Success ->
                Text(
                    stringResource(R.string.models_test_ok, testResult.dimensions, testResult.latencyMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            is EmbeddingTestResult.Failure ->
                Text(stringResource(R.string.models_test_failed, testResult.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> {}
        }
    }
}

@Composable
private fun InstalledTranscriptionModelRow(
    file: File,
    isActive: Boolean,
    isResident: Boolean,
    testing: Boolean,
    testingApi: Boolean,
    testResult: TranscriptionTestResult?,
    onSetActive: () -> Unit,
    onTest: () -> Unit,
    onTestApi: () -> Unit,
    onDelete: () -> Unit
) {
    InstalledModelRowShell(
        file = file,
        isActive = isActive,
        isResident = isResident,
        testing = testing,
        testingApi = testingApi,
        onSetActive = onSetActive,
        onTest = onTest,
        onTestApi = onTestApi,
        onDelete = onDelete
    ) {
        when (testResult) {
            is TranscriptionTestResult.Success ->
                Text(
                    stringResource(R.string.models_test_transcription_ok, testResult.text, testResult.latencyMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            is TranscriptionTestResult.Failure ->
                Text(stringResource(R.string.models_test_failed, testResult.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> {}
        }
    }
}

/** Shared row chrome for both kinds — filename/active badge/delete up top, test-result slot in
 * the middle (kind-specific content), set-active/test buttons at the bottom.
 *
 * [isActive] (registry's chosen file for this kind, the checkmark badge) and [isResident]
 * (this kind's engine actually running right now) are deliberately separate: a model stays
 * registry-active forever once picked, even while the OTHER kind is the one resident (mutual
 * exclusion, see ModelRuntimeService kdoc) and this one is stopped. Gating "Usar este modelo"
 * on [isActive] instead of [isResident] would hide the only action that re-promotes this kind
 * to resident once it's already registry-active — leaving "Testar" (which always restores the
 * previous resident engine afterward) as the sole option forever.
 *
 * Two distinct test actions, per user request after a confusing on-device failure that turned
 * out to be a server-switch race rather than a model problem: [onTest] runs the model directly
 * (llama-embedding/whisper-cli, no HTTP — see [ModelRuntimeService.handleTestLocal]) and
 * [onTestApi] runs the full server+API pipeline (see [ModelRuntimeService.handleTestApi]),
 * always restoring whatever was resident before. Splitting them makes it obvious which layer
 * actually broke instead of one combined pass/fail. */
@Composable
private fun InstalledModelRowShell(
    file: File,
    isActive: Boolean,
    isResident: Boolean,
    testing: Boolean,
    testingApi: Boolean,
    onSetActive: () -> Unit,
    onTest: () -> Unit,
    onTestApi: () -> Unit,
    onDelete: () -> Unit,
    testResultContent: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.models_active), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Text(file.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.models_remove))
                }
            }

            testResultContent()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isResident) {
                    OutlinedButton(onClick = onSetActive) { Text(stringResource(R.string.models_use_this_model)) }
                }
                OutlinedButton(onClick = onTest, enabled = !testing && !testingApi) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                    }
                    Text(stringResource(R.string.models_test))
                }
                OutlinedButton(onClick = onTestApi, enabled = !testing && !testingApi) {
                    if (testingApi) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                    }
                    Text(stringResource(if (isResident) R.string.models_test_api else R.string.models_test_api_restarts_server))
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    summary: HFModelSummary,
    expanded: Boolean,
    files: List<HFModelFile>,
    loadingFiles: Boolean,
    downloadingFile: String?,
    downloadProgress: Float,
    onToggle: () -> Unit,
    onDownload: (HFModelFile) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(summary.repoId, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.models_search_downloads, summary.downloads), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onToggle) { Text(stringResource(if (expanded) R.string.models_close else R.string.models_view_files)) }
            }

            if (expanded) {
                if (loadingFiles) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (files.isEmpty()) {
                    Text(stringResource(R.string.models_no_gguf_files), style = MaterialTheme.typography.bodySmall)
                } else {
                    files.forEach { file ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(file.fileName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (downloadingFile == file.fileName) {
                                Column(horizontalAlignment = Alignment.End) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.size(width = 80.dp, height = 4.dp)
                                    )
                                    Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Button(onClick = { onDownload(file) }, enabled = downloadingFile == null) {
                                    Text(stringResource(R.string.models_download))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
