package io.legado.app.ui.config

import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.UUID
import android.net.Uri
import androidx.core.content.FileProvider
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.legado.app.R
import io.legado.app.data.entities.AiMediaGeneration
import io.legado.app.help.ai.AiConfig
import io.legado.app.help.ai.AiImageParams
import io.legado.app.help.ai.AiMediaFieldDetector
import io.legado.app.help.ai.AiMediaHelper
import io.legado.app.help.ai.AiMediaKind
import io.legado.app.help.ai.AiMediaProgress
import io.legado.app.help.ai.AiMediaTaskState
import io.legado.app.help.ai.AiModel
import io.legado.app.help.ai.AiModelType
import io.legado.app.help.ai.AiMediaProtocol
import io.legado.app.help.ai.AiMediaReference
import io.legado.app.help.ai.AiProviderStore
import io.legado.app.data.entities.AiMediaProject
import io.legado.app.help.ai.AiVideoParams
import io.legado.app.ui.design.theme.NgAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 创作页：图片 / 视频生成 + 历史 + 自定义协议族「傻瓜式」自动识别。
 *
 * 设计原则：
 * - 复用 [AiMediaManager] / [AiMediaHelper]，不关心厂商差异
 * - 媒体协议族选择、端点覆盖、JSONPath 自动识别都内聚在此，便于以后做 AI 漫画 / 漫剧
 *   时直接复用（漫画 = 多张图 + 角色垫图；漫剧 = 多镜头视频 + 首尾帧）
 */
class AiMediaGenerateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NgAppTheme {
                AiMediaGenerateScreen()
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AiMediaGenerateScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AiMediaKind.IMAGE) }

    val providers = remember { AiProviderStore.providers() }
    var imageProviderId by remember { mutableStateOf(AiConfig.mediaImageProviderId.ifBlank { providers.firstOrNull()?.id.orEmpty() }) }
    var videoProviderId by remember { mutableStateOf(AiConfig.mediaVideoProviderId.ifBlank { providers.firstOrNull()?.id.orEmpty() }) }
    var imageModel by remember { mutableStateOf(AiConfig.mediaImageModelId) }
    var videoModel by remember { mutableStateOf(AiConfig.mediaVideoModelId) }

    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var sizeText by remember { mutableStateOf("") }
    var ratioText by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var seedText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("") }
    var firstFrame by remember { mutableStateOf("") }
    var lastFrame by remember { mutableStateOf("") }

    var advancedExpanded by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AiMediaProgress?>(null) }
    var generating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<String>() }
    val history = remember { mutableStateListOf<AiMediaGeneration>() }

    // 项目选择：把本次生成的分镜归入命名项目，否则全部掉进「未归类」，
    // 在媒体库里既无法按项目分组，也无法拼成漫画长图 / 漫剧视频。
    var projectId by remember { mutableStateOf("") }
    val projects = remember { mutableStateListOf<AiMediaProject>() }
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var showNewProject by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    // 查库一律走 IO：组合期同步读 Room 会在历史多了以后掉帧，和媒体库页保持一致
    fun refreshHistory() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { AiMediaHelper.history() }
            history.clear()
            history.addAll(list)
        }
    }

    // 从 AI 媒体库返回时同步删除结果：历史原来只在启动时加载一次，
    // 不监听 ON_RESUME 的话，库中删掉的记录回到本页仍会残留。
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeTick) { refreshHistory() }
    // 项目列表只需首帧加载一次；新建项目后直接 add 到列表，不必重新查库
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { AiMediaHelper.projects() }
        projects.clear()
        projects.addAll(list)
    }

    fun runGenerate() {
        if (prompt.isBlank()) {
            errorText = context.getString(R.string.ai_media_prompt_required)
            return
        }
        errorText = null
        results.clear()
        generating = true
        progress = AiMediaProgress(AiMediaTaskState.PENDING, message = context.getString(R.string.ai_media_submitting))
        scope.launch {
            runCatching {
                if (tab == AiMediaKind.IMAGE) {
                    AiConfig.saveMediaModel(AiMediaKind.IMAGE, imageProviderId, imageModel)
                    val params = AiImageParams(
                        prompt = prompt,
                        negativePrompt = negativePrompt.ifBlank { null },
                        size = sizeText.ifBlank { null },
                        aspectRatio = ratioText.ifBlank { null },
                        quality = quality.ifBlank { null },
                        style = style.ifBlank { null },
                        seed = seedText.toLongOrNull()
                    )
                    AiMediaHelper.generateImage(
                        params, imageProviderId, imageModel.ifBlank { null },
                        projectId = projectId, shotIndex = 0
                    ) {
                        progress = it
                    }
                } else {
                    AiConfig.saveMediaModel(AiMediaKind.VIDEO, videoProviderId, videoModel)
                    val params = AiVideoParams(
                        prompt = prompt,
                        negativePrompt = negativePrompt.ifBlank { null },
                        durationSeconds = durationText.toIntOrNull(),
                        aspectRatio = ratioText.ifBlank { null },
                        resolution = resolution.ifBlank { null },
                        seed = seedText.toLongOrNull(),
                        firstFrame = firstFrame.ifBlank { null }?.let { AiMediaReference(source = it) },
                        lastFrame = lastFrame.ifBlank { null }?.let { AiMediaReference(source = it) }
                    )
                    AiMediaHelper.generateVideo(
                        params, videoProviderId, videoModel.ifBlank { null },
                        projectId = projectId, shotIndex = 0
                    ) {
                        progress = it
                    }
                }
            }.onSuccess { entity ->
                results.addAll(AiMediaHelper.displayPaths(entity))
                refreshHistory()
            }.onFailure {
                errorText = it.message ?: it.localizedMessage ?: context.getString(R.string.ai_media_failed)
            }
            generating = false
            progress = null
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.ai_media_title)) },
                navigationIcon = {
                    TextButton(onClick = { (context as? AppCompatActivity)?.finish() }) {
                        Text(stringResource(R.string.exit))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        context.startActivity(Intent(context, AiMediaLibraryActivity::class.java))
                    }) {
                        Text(stringResource(R.string.ai_media_library_open))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TabRow(selectedTabIndex = if (tab == AiMediaKind.IMAGE) 0 else 1) {
                Tab(
                    selected = tab == AiMediaKind.IMAGE,
                    onClick = { tab = AiMediaKind.IMAGE },
                    text = { Text(stringResource(R.string.ai_media_tab_image)) }
                )
                Tab(
                    selected = tab == AiMediaKind.VIDEO,
                    onClick = { tab = AiMediaKind.VIDEO },
                    text = { Text(stringResource(R.string.ai_media_tab_video)) }
                )
            }

            val currentProviderId = if (tab == AiMediaKind.IMAGE) imageProviderId else videoProviderId
            ProviderSelector(
                label = stringResource(R.string.ai_media_provider),
                selectedId = currentProviderId,
                providers = providers.filter { it.supportsKind(tab) }
                    .ifEmpty { providers }
                    .map { it.id to it.name },
                onSelected = {
                    if (tab == AiMediaKind.IMAGE) imageProviderId = it else videoProviderId = it
                }
            )
            val currentModel = if (tab == AiMediaKind.IMAGE) imageModel else videoModel
            val wantedType = if (tab == AiMediaKind.IMAGE) AiModelType.IMAGE else AiModelType.VIDEO
            val providerModels = providers.firstOrNull { it.id == currentProviderId }?.models.orEmpty()
            // 优先列出同类型模型；该 provider 未标注类型时退化为全部模型，仍支持手填
            val suggestedModels = providerModels.filter { it.type == wantedType }
                .ifEmpty { providerModels }
            ModelField(
                label = stringResource(R.string.ai_media_model),
                value = currentModel,
                models = suggestedModels,
                onValueChange = { if (tab == AiMediaKind.IMAGE) imageModel = it else videoModel = it }
            )

            // 项目选择：本次生成归入命名项目；留空则掉进「未归类」，无法在媒体库里分组或合成。
            val currentProjectName = projects.firstOrNull { it.id == projectId }?.name
                ?: stringResource(R.string.ai_media_project_unclassified)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { projectMenuExpanded = true }) {
                    Text(stringResource(R.string.ai_media_project) + "：$currentProjectName")
                }
                DropdownMenu(
                    expanded = projectMenuExpanded,
                    onDismissRequest = { projectMenuExpanded = false }
                ) {
                    if (projects.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_media_project_none)) },
                            onClick = { projectMenuExpanded = false }
                        )
                    }
                    projects.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name.ifBlank { p.id }) },
                            onClick = {
                                projectId = p.id
                                projectMenuExpanded = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_media_project_new)) },
                        onClick = {
                            projectMenuExpanded = false
                            showNewProject = true
                        }
                    )
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.ai_media_prompt)) },
                placeholder = { Text(stringResource(R.string.ai_media_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
            OutlinedTextField(
                value = negativePrompt,
                onValueChange = { negativePrompt = it },
                label = { Text(stringResource(R.string.ai_media_negative)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (tab == AiMediaKind.IMAGE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sizeText,
                        onValueChange = { sizeText = it },
                        label = { Text(stringResource(R.string.ai_media_size)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ratioText,
                        onValueChange = { ratioText = it },
                        label = { Text(stringResource(R.string.ai_media_aspect_ratio)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quality,
                        onValueChange = { quality = it },
                        label = { Text(stringResource(R.string.ai_media_quality)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = style,
                        onValueChange = { style = it },
                        label = { Text(stringResource(R.string.ai_media_style)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it },
                        label = { Text(stringResource(R.string.ai_media_duration)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = ratioText,
                        onValueChange = { ratioText = it },
                        label = { Text(stringResource(R.string.ai_media_aspect_ratio)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = resolution,
                        onValueChange = { resolution = it },
                        label = { Text(stringResource(R.string.ai_media_resolution)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = firstFrame,
                        onValueChange = { firstFrame = it },
                        label = { Text(stringResource(R.string.ai_media_first_frame)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = lastFrame,
                    onValueChange = { lastFrame = it },
                    label = { Text(stringResource(R.string.ai_media_last_frame)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = seedText,
                onValueChange = { seedText = it },
                label = { Text(stringResource(R.string.ai_media_seed)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )

            AdvancedMediaConfig(currentProviderId = currentProviderId)

            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advancedExpanded) "▾ ${stringResource(R.string.ai_media_advanced)}" else "▸ ${stringResource(R.string.ai_media_advanced)}")
            }

            if (generating) {
                val p = progress
                LinearProgressIndicator(
                    progress = { (p?.percent?.toFloat()?.div(100f)) ?: 0.05f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(p?.message ?: "", style = MaterialTheme.typography.bodySmall)
            }

            androidx.compose.material3.Button(
                onClick = { runGenerate() },
                enabled = !generating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (generating) stringResource(R.string.ai_media_generating) else stringResource(R.string.ai_media_generate))
            }

            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (results.isNotEmpty()) {
                Text(stringResource(R.string.ai_media_result), style = MaterialTheme.typography.titleSmall)
                results.forEach { path ->
                    ResultPreview(path = path, isVideo = tab == AiMediaKind.VIDEO) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val targetId = AiMediaHelper.history()
                                    .firstOrNull { g -> AiMediaHelper.displayPaths(g).contains(path) }?.id
                                // 找不到就别调 delete("")，否则只是白白多一次查库
                                if (!targetId.isNullOrBlank()) AiMediaHelper.delete(targetId)
                            }
                            results.remove(path)
                            refreshHistory()
                        }
                    }
                }
            }

            Text(stringResource(R.string.ai_media_history), style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) {
                Text(stringResource(R.string.ai_media_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                history.forEach { entity ->
                    HistoryCard(entity = entity) { path ->
                        when {
                            entity.kind == AiMediaKind.VIDEO.prefValue -> {
                                val videoUri = mediaContentUri(context, path)
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(videoUri, "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(intent) }
                            }
                            else -> {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, mediaContentUri(context, path))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(share, context.getString(R.string.ai_media_share))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showNewProject) {
            AlertDialog(
                onDismissRequest = { showNewProject = false },
                title = { Text(stringResource(R.string.ai_media_project_new)) },
                text = {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text(stringResource(R.string.ai_media_project_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newProjectName.isNotBlank(),
                        onClick = {
                            val name = newProjectName.trim()
                            if (name.isNotBlank()) {
                                val id = UUID.randomUUID().toString()
                                val kind = if (tab == AiMediaKind.IMAGE) "image" else "video"
                                // ensureProject 是一次读 + 一次写，同样不能在点击回调里同步跑
                                scope.launch {
                                    val created = withContext(Dispatchers.IO) {
                                        AiMediaHelper.ensureProject(id, name, kind)
                                    }
                                    projects.add(created)
                                    projectId = created.id
                                }
                            }
                            newProjectName = ""
                            showNewProject = false
                        }
                    ) { Text(stringResource(R.string.ai_media_project_create)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        newProjectName = ""
                        showNewProject = false
                    }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
private fun ProviderSelector(
    label: String,
    selectedId: String,
    providers: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = providers.firstOrNull { it.first == selectedId }?.second ?: selectedId,
            onValueChange = {},
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            trailingIcon = {
                TextButton(onClick = { expanded = true }) { Text("▾") }
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onSelected(id)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun AdvancedMediaConfig(currentProviderId: String) {
    val context = LocalContext.current
    var protocolExpanded by remember { mutableStateOf(false) }
    var protocol by remember {
        mutableStateOf(
            AiProviderStore.provider(currentProviderId)?.resolvedMediaProtocol?.prefValue ?: AiMediaProtocol.OPENAI.prefValue
        )
    }
    var pasteText by remember { mutableStateOf("") }
    var detected by remember { mutableStateOf<List<AiMediaFieldDetector.Option>>(emptyList()) }
    var taskIdPath by remember { mutableStateOf("") }
    var statusPath by remember { mutableStateOf("") }
    var resultPath by remember { mutableStateOf("") }
    var errorPath by remember { mutableStateOf("") }

    val protocols = AiMediaProtocol.entries.map { it.prefValue to it.displayName }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderSelector(
            label = stringResource(R.string.ai_media_protocol),
            selectedId = protocol,
            providers = protocols,
            onSelected = { protocol = it }
        )
        if (protocol == AiMediaProtocol.CUSTOM.prefValue) {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text(stringResource(R.string.ai_media_detect)) },
                placeholder = { Text(stringResource(R.string.ai_media_detect_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            androidx.compose.material3.Button(onClick = {
                detected = AiMediaFieldDetector.detectFromText(pasteText).orEmpty()
            }) {
                Text(stringResource(R.string.ai_media_detect_button))
            }
            if (detected.isNotEmpty()) {
                Text(stringResource(R.string.ai_media_detect_result), style = MaterialTheme.typography.bodySmall)
                DetectedPicker(
                    title = stringResource(R.string.ai_media_task_id),
                    options = detected.filter { it.category == AiMediaFieldDetector.Category.TASK_ID },
                    selected = taskIdPath,
                    onSelected = { taskIdPath = it }
                )
                DetectedPicker(
                    title = stringResource(R.string.ai_media_status),
                    options = detected.filter { it.category == AiMediaFieldDetector.Category.STATUS },
                    selected = statusPath,
                    onSelected = { statusPath = it }
                )
                DetectedPicker(
                    title = stringResource(R.string.ai_media_result_url),
                    options = detected.filter { it.category == AiMediaFieldDetector.Category.RESULT_URL },
                    selected = resultPath,
                    onSelected = { resultPath = it }
                )
                DetectedPicker(
                    title = stringResource(R.string.ai_media_error),
                    options = detected.filter { it.category == AiMediaFieldDetector.Category.ERROR },
                    selected = errorPath,
                    onSelected = { errorPath = it }
                )
            }
        }
        // 保存始终可用：火山方舟 / 万相 / 可灵 / Google 等非自定义协议族同样需要落盘，
        // 否则用户选了协议却无法持久化，实际仍会回落到按 Provider 类型推断的结果。
        androidx.compose.material3.Button(onClick = {
            val setting = AiProviderStore.provider(currentProviderId) ?: return@Button
            AiProviderStore.saveProvider(
                setting.copy(
                    mediaProtocol = protocol,
                    mediaTaskIdPath = taskIdPath,
                    mediaStatusPath = statusPath,
                    mediaResultPath = resultPath,
                    mediaErrorPath = errorPath
                )
            )
            Toast.makeText(context, R.string.ai_media_saved, Toast.LENGTH_SHORT).show()
        }) {
            Text(stringResource(R.string.ai_media_apply))
        }
    }
}

@Composable
private fun DetectedPicker(
    title: String,
    options: List<AiMediaFieldDetector.Option>,
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.weight(0.4f))
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { expanded = true }) { Text("▾") } }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ai_media_auto)) }, onClick = {
                    onSelected("")
                    expanded = false
                })
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt.label) }, onClick = {
                        onSelected(opt.path)
                        expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun ResultPreview(path: String, isVideo: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().clickable { onDelete() }) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (isVideo) {
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val videoUri = mediaContentUri(context, path)
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(videoUri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.ai_media_play)) }
            } else {
                // 解码是磁盘 + CPU 活，放主线程会在滚动历史列表时掉帧
                val bitmap by produceState<Bitmap?>(null, path) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
                }
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )
                } else {
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.ai_media_delete)) }
        }
    }
}

@Composable
private fun HistoryCard(entity: AiMediaGeneration, onOpen: (String) -> Unit) {
    val paths = remember(entity.id) { AiMediaHelper.displayPaths(entity) }
    Card(modifier = Modifier.fillMaxWidth().clickable { paths.firstOrNull()?.let(onOpen) }) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entity.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                "${entity.model} · ${entity.kind} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(entity.createdAt))}",
                style = MaterialTheme.typography.bodySmall
            )
            if (entity.kind == AiMediaKind.IMAGE.prefValue && paths.firstOrNull()?.let { it.startsWith("/") } == true) {
                val firstPath = paths.firstOrNull()
                val bitmap by produceState<Bitmap?>(null, entity.id, firstPath) {
                    value = firstPath?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it) } }
                }
                val bmp = bitmap
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(160.dp))
                }
            }
            if (paths.isNotEmpty()) {
                TextButton(onClick = { paths.firstOrNull()?.let(onOpen) }) {
                    Text(if (entity.kind == AiMediaKind.VIDEO.prefValue) stringResource(R.string.ai_media_play) else stringResource(R.string.ai_media_open))
                }
            }
        }
    }
}

/**
 * 经 FileProvider 暴露本地媒体文件。
 *
 * 直接把文件路径转成 file:// URI 分享给外部 App，在 Android 7.0+ 会抛
 * FileUriExposedException 直接崩溃；这里统一换成 content:// 并授予临时读权限。
 */
private fun mediaContentUri(context: android.content.Context, path: String): Uri {
    val file = File(path)
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
    }.getOrElse { file.toUri() }
}

/** 模型输入：可直接手填，也可从该服务商已配置的同类型模型中挑选 */
@Composable
private fun ModelField(
    label: String,
    value: String,
    models: List<AiModel>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (models.isNotEmpty()) {
                    TextButton(onClick = { expanded = true }) { Text("\u25be") }
                }
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName.ifBlank { model.name }) },
                    onClick = {
                        onValueChange(model.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
