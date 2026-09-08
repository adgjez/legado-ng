package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 媒体生成历史（图片 / 视频）。
 *
 * 一条记录对应一次生成任务，可能产出多个结果文件。结果以 JSON 数组落库
 * （见 [AiMediaHelper] 写入的 [io.legado.app.help.ai.AiMediaStoredResult]），
 * 不直接展开成多列是为了兼容「一次出 N 张图」以及漫画/漫剧的分镜产物。
 *
 * [projectId] / [shotIndex] 为 AI 漫画、AI 漫剧预留：同一 projectId 下按 shotIndex
 * 串联分镜，保持角色与镜头连贯。
 */
@Entity(
    tableName = "aiMediaGenerations",
    indices = [
        Index(value = ["kind"]),
        Index(value = ["projectId"]),
        Index(value = ["createdAt"])
    ]
)
data class AiMediaGeneration(
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    val id: String,
    @ColumnInfo(defaultValue = "image")
    val kind: String,
    @ColumnInfo(defaultValue = "")
    val providerId: String,
    @ColumnInfo(defaultValue = "")
    val model: String,
    @ColumnInfo(defaultValue = "")
    val prompt: String,
    @ColumnInfo(defaultValue = "")
    val negativePrompt: String,
    /** 生成参数快照（尺寸/比例/质量/种子等），JSON 字符串 */
    @ColumnInfo(defaultValue = "")
    val paramJson: String,
    /** 结果列表 JSON，每项含本地路径、远程地址与 mimeType */
    @ColumnInfo(defaultValue = "")
    val resultsJson: String,
    /** 漫画/漫剧项目 id，通用生成留空 */
    @ColumnInfo(defaultValue = "")
    val projectId: String,
    @ColumnInfo(defaultValue = "0")
    val shotIndex: Int,
    @ColumnInfo(defaultValue = "0")
    val seed: Long,
    @ColumnInfo(defaultValue = "0")
    val durationSeconds: Int,
    @ColumnInfo(defaultValue = "0")
    val width: Int,
    @ColumnInfo(defaultValue = "0")
    val height: Int,
    @ColumnInfo(defaultValue = "")
    val revisedPrompt: String,
    @ColumnInfo(defaultValue = "success")
    val status: String,
    @ColumnInfo(defaultValue = "")
    val errorMessage: String,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long
)
