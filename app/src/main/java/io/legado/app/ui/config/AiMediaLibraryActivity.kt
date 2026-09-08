package io.legado.app.ui.config

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.data.entities.AiMediaGeneration
import io.legado.app.help.ai.AiMediaComposer
import io.legado.app.help.ai.AiMediaHelper
import io.legado.app.help.ai.AiMediaKind
import io.legado.app.ui.design.theme.NgAppTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 媒体库：独立的可浏览图库。
 *
 * - 图片 / 视频混合网格（视频取首帧缩略图）
 * - 按 [AiMediaGeneration.projectId] 分组：漫画 / 漫剧的项目归到一起，通用生成单列
 * - 点按打开（图片走查看、视频走播放），长按删除（本地文件 + 历史一并清除）
 *
 * 数据全部来自 [AiMediaHelper]，不重复造存储。
 */
class AiMediaLibraryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NgAppTheme {
                AiMediaLibraryScreen()
            }
        }
    }
}

private data class LibItem(
    val entity: AiMediaGeneration,
    val path: String,
    val isVideo: Boolean,
    /** 配音产物（wav）：没有缩略图，角标、占位文案与打开方式都要单独处理 */
    val isAudio: Boolean
)

private sealed class LibRow {
    /**
     * 分组标题行。
     *
     * 这里只存 [projectName]（空串表示「未归类/通用」），**不存拼好的 title 字符串**：
     * 标题要拼 `stringResource(...)`，而 rows 是在 [buildRows] 里（IO 协程，非
     * @Composable 上下文）算出来的，那里不允许调用 @Composable 函数。
     * 真正的文案在渲染时才拼。
     */
    data class Header(
        val key: String,
        val projectName: String,
        /**
         * 该分组可执行的合成动作：**"comic"** 拼漫画长图、**"drama"** 拼漫剧视频。
         * 空串表示不可合成——「未归类/通用」分组没有 projectId，分镜彼此无关，
         * 硬拼只会产出毫无意义的长图/视频。
         *
         * 在 [buildRows] 里算好（IO 线程可安全读库），标题行只负责渲染与回传。
         */
        val composeKind: String
    ) : LibRow()

    data class Cell(val item: LibItem) : LibRow()
}

/**
 * 组装网格行（分组表头 + 单元格）。
 *
 * 这是**普通函数**，跑在 [AiMediaLibraryScreen] 的 IO 协程里，所以绝不调用任何
 * @Composable（尤其是 `stringResource`）——分组标题的文案留到渲染时再拼。
 */
private fun buildRows(filter: Pair<AiMediaKind, AiMediaKind>): List<LibRow> {
    val all = AiMediaHelper.history()
    val items = all.flatMap { entity ->
        val video = entity.kind == AiMediaKind.VIDEO.prefValue
        val audio = entity.kind == AiMediaKind.AUDIO.prefValue
        AiMediaHelper.displayPaths(entity).map { path ->
            LibItem(entity = entity, path = path, isVideo = video, isAudio = audio)
        }
    }.filter { item ->
        when {
            // 配音产物没有缩略图，只在「全部」里出现，免得混进图片/视频的筛选结果
            item.isAudio -> filter.first == AiMediaKind.IMAGE && filter.second == AiMediaKind.VIDEO
            item.isVideo -> filter.second == AiMediaKind.VIDEO
            else -> filter.first == AiMediaKind.IMAGE
        }
    }
    // 保持历史降序：先按首次出现顺序分组，组内保持原序
    val projectById = AiMediaHelper.projects().associateBy { it.id }
    val order = mutableListOf<String>()
    val buckets = linkedMapOf<String, MutableList<LibItem>>()
    items.forEach { item ->
        val key = item.entity.projectId.ifBlank { "" }
        if (key !in buckets) {
            buckets[key] = mutableListOf()
            order.add(key)
        }
        buckets[key]!!.add(item)
    }
    val rows = mutableListOf<LibRow>()
    order.forEach { key ->
        val group = buckets[key]!!
        rows.add(
            LibRow.Header(
                key = key,
                projectName = projectById[key]?.name ?: "",
                composeKind = resolveComposeKind(key, projectById[key]?.kind, group)
            )
        )
        group.forEach { rows.add(LibRow.Cell(it)) }
    }
    return rows
}

/**
 * 决定一个项目分组该提供哪种合成动作。
 *
 * 优先采信项目声明的 [AiMediaProject.kind]；`mixed` 或读不到时，
 * 退回按分镜实际类型判断（有视频就拼视频，否则拼长图）。
 *
 * @return **"comic"** 拼长图 / **"drama"** 拼视频 / 空串＝不提供合成入口
 */
private fun resolveComposeKind(
    projectId: String,
    declaredKind: String?,
    group: List<LibItem>
): String {
    // 「未归类/通用」分组没有 projectId，分镜彼此无关，硬拼只会产出毫无意义的长图/视频
    if (projectId.isBlank()) return ""
    // 配音不参与画面合成
    val shots = group.filterNot { it.isAudio }
    if (shots.isEmpty()) return ""
    return when (declaredKind) {
        AiMediaKind.VIDEO.prefValue -> "drama"
        AiMediaKind.IMAGE.prefValue -> "comic"
        else -> if (shots.any { it.isVideo }) "drama" else "comic"
    }
}

/** 一次 App 内合成请求；[isDrama] 决定走 [AiMediaComposer.composeDrama] 还是 composeComic */
private data class ComposeRequest(val projectId: String, val isDrama: Boolean)

@Composable
private fun AiMediaLibraryScreen() {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(AiMediaKind.IMAGE to AiMediaKind.VIDEO) } // ALL
    var version by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<LibItem?>(null) }

    var rows by remember { mutableStateOf<List<LibRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var composeRequest by remember { mutableStateOf<ComposeRequest?>(null) }

    // 查库搬到 LaunchedEffect 的 IO 协程里：组合期同步读库，历史一多就会掉帧。
    // 切换筛选或删除条目后 version/filter 变化，这里会自动重跑。
    LaunchedEffect(version, filter) {
        loading = true
        rows = withContext(Dispatchers.IO) { buildRows(filter) }
        loading = false
    }

    // 合成是重活（Canvas 拼长图 / MediaMuxer 拼视频），必须离开主线程，否则直接 ANR。
    // 成功后把成片写回媒体库并 version++ 触发刷新；失败显式 toast，绝不静默。
    LaunchedEffect(composeRequest) {
        val req = composeRequest ?: return@LaunchedEffect
        // composeComic/composeDrama 返回 Result<File>，persistComposed 返回 Result<AiMediaGeneration>。
        // mapCatching 串联：任一步失败都短路成 Result.failure（携带 AiMediaError）。
        val entityResult: Result<AiMediaGeneration> = withContext(Dispatchers.IO) {
            val composer = if (req.isDrama) {
                AiMediaComposer.composeDrama(req.projectId)
            } else {
                AiMediaComposer.composeComic(req.projectId)
            }
            composer.mapCatching { file ->
                AiMediaHelper.persistComposed(
                    kind = if (req.isDrama) AiMediaKind.VIDEO else AiMediaKind.IMAGE,
                    file = file,
                    projectId = req.projectId,
                    format = if (req.isDrama) "drama" else "comic"
                ).getOrThrow()
            }
        }
        // mapCatching 可能把取消异常也包成 failure，这里还原，否则会吞掉协程取消信号
        (entityResult.exceptionOrNull() as? CancellationException)?.let { throw it }
        // 置空后本 effect 会以 null 重启并立即返回，不会循环
        composeRequest = null
        entityResult.onSuccess {
            Toast.makeText(context, R.string.ai_media_library_compose_done, Toast.LENGTH_SHORT).show()
            version++
        }.onFailure { e ->
            Toast.makeText(
                context,
                context.getString(R.string.ai_media_library_compose_failed) + "：" + (e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_media_library_title)) },
                navigationIcon = {
                    TextButton(onClick = { (context as? AppCompatActivity)?.finish() }) {
                        Text(stringResource(R.string.exit))
                    }
                },
                actions = {
                    // 筛选：全部 / 图片 / 视频
                    TextButton(onClick = {
                        filter = when {
                            filter.first == AiMediaKind.IMAGE && filter.second == AiMediaKind.VIDEO ->
                                AiMediaKind.IMAGE to AiMediaKind.IMAGE
                            filter.first == AiMediaKind.IMAGE ->
                                AiMediaKind.VIDEO to AiMediaKind.VIDEO
                            else ->
                                AiMediaKind.IMAGE to AiMediaKind.VIDEO
                        }
                        version++
                    }) {
                        val label = when {
                            filter.first == AiMediaKind.IMAGE && filter.second == AiMediaKind.VIDEO ->
                                stringResource(R.string.ai_media_library_filter_all)
                            filter.first == AiMediaKind.IMAGE ->
                                stringResource(R.string.ai_media_library_filter_image)
                            else ->
                                stringResource(R.string.ai_media_library_filter_video)
                        }
                        Text(label)
                    }
                    TextButton(onClick = { version++ }) {
                        Text(stringResource(R.string.ai_media_library_refresh))
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            rows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.ai_media_library_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                items(
                    count = rows.size,
                    key = { index -> rows[index].let { if (it is LibRow.Header) "h:${it.key}" else "c:${it.item.entity.id}:${it.item.path}" } },
                    span = { index ->
                        val row = rows[index]
                        GridItemSpan(if (row is LibRow.Header) maxLineSpan else 1)
                    }
                ) { index ->
                    when (val row = rows[index]) {
                        is LibRow.Header -> {
                            val title = if (row.projectName.isBlank()) {
                                stringResource(R.string.ai_media_library_general)
                            } else {
                                "${stringResource(R.string.ai_media_library_project)}：${row.projectName}"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (row.composeKind.isNotBlank()) {
                                    TextButton(
                                        // 合成中禁用，避免同一项目被并发拼装
                                        enabled = composeRequest == null,
                                        onClick = {
                                            composeRequest = ComposeRequest(
                                                projectId = row.key,
                                                isDrama = row.composeKind == "drama"
                                            )
                                        }
                                    ) {
                                        Text(
                                            if (row.composeKind == "drama") {
                                                stringResource(R.string.ai_media_library_compose_drama)
                                            } else {
                                                stringResource(R.string.ai_media_library_compose_comic)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        is LibRow.Cell -> {
                            MediaCell(
                                item = row.item,
                                onOpen = { openItem(context, it) },
                                onLongPress = { pendingDelete = it }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // 合成进度：拼长图/拼视频是秒级阻塞操作，必须给出可见反馈，
    // 否则用户会以为按钮没生效而反复点击。
    if (composeRequest != null) {
        AlertDialog(
            // 合成期间禁止关闭：中途 dismiss 会让用户以为取消了，但协程仍在跑
            onDismissRequest = { },
            title = { Text(stringResource(R.string.ai_media_library_compose_running)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(end = 12.dp)
                    )
                    Text(
                        if (composeRequest?.isDrama == true) {
                            stringResource(R.string.ai_media_library_compose_drama)
                        } else {
                            stringResource(R.string.ai_media_library_compose_comic)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = { }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ai_media_library_delete_title)) },
            text = { Text(stringResource(R.string.ai_media_library_delete_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    AiMediaHelper.delete(target.entity.id)
                    pendingDelete = null
                    version++
                    Toast.makeText(context, R.string.ai_media_library_deleted, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.ai_media_library_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.ai_media_library_cancel))
                }
            }
        )
    }
}

@Composable
private fun MediaCell(
    item: LibItem,
    onOpen: (LibItem) -> Unit,
    onLongPress: (LibItem) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(item) },
                onLongClick = { onLongPress(item) }
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 缩略图解码（尤其视频抽帧）放到协程里，避免组合期同步读文件导致滚动掉帧
            val bitmap by produceState<Bitmap?>(null, item.path) {
                value = withContext(Dispatchers.IO) { loadThumb(item) }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().size(120.dp)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().size(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            item.isAudio -> stringResource(R.string.audio)
                            item.isVideo -> stringResource(R.string.ai_media_library_video)
                            else -> stringResource(R.string.ai_media_library_image)
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // 角标：类型 + 时长（视频）+ 分镜号
            val badge = buildList {
                add(if (item.isVideo) "▶" else if (item.isAudio) "♪" else "图")
                if (item.isVideo && item.entity.durationSeconds > 0) {
                    add(formatDuration(item.entity.durationSeconds))
                }
                if (item.entity.shotIndex > 0) {
                    add(stringResource(R.string.ai_media_library_shot, item.entity.shotIndex))
                }
            }.joinToString(" ")
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            )
        }
        // 提示文案（点击打开，长按删除）
        Text(
            item.entity.prompt.ifBlank { item.entity.model },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp)
        )
    }
}

/** 秒 → m:ss / h:mm:ss。显式用 Locale.US，避免部分语言下输出本地化数字。 */
private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** 图片直接解码；视频取首帧缩略图，失败回退 null（由调用方显示占位）。 */
private fun loadThumb(item: LibItem): Bitmap? {
    // 音频没有可解码的画面，直接走占位，别拿 BitmapFactory / MediaMetadataRetriever 去啃 wav
    if (item.isAudio) return null
    return runCatching {
        if (item.isVideo) {
            val ret = MediaMetadataRetriever()
            try {
                ret.setDataSource(item.path)
                ret.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: ret.frameAtTime
            } finally {
                runCatching { ret.release() }
            }
        } else {
            BitmapFactory.decodeFile(item.path)
        }
    }.getOrNull()
}

private fun openItem(context: android.content.Context, item: LibItem) {
    val uri = mediaContentUri(context, item.path)
    val type = when {
        item.isVideo -> "video/*"
        item.isAudio -> "audio/*"
        else -> "image/*"
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(intent, item.entity.prompt.ifBlank { null }))
    }.onFailure {
        Toast.makeText(context, R.string.ai_media_library_open_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 经 FileProvider 暴露本地媒体文件（避免 Android 7.0+ 的 FileUriExposedException）。
 * 与 [AiMediaGenerateActivity] 中同名函数保持一致。
 */
private fun mediaContentUri(context: android.content.Context, path: String): Uri {
    val file = java.io.File(path)
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
    }.getOrElse { file.toUri() }
}
