# 工具协议（AI 漫剧）

漫剧用到 `ai_media_generate_image` 和 `ai_media_generate_video` 两个工具。核心链路：**关键帧图先于视频，视频首尾帧接下一镜关键帧**。

## 1. 关键帧图：`ai_media_generate_image`

逐镜串行，后一镜依赖前一镜的关键帧。

| 参数 | 说明 | 备注 |
|---|---|---|
| `prompt` | 本镜关键帧画面 | 固定角色圣经前缀 + 动作 + 环境光影 + 风格 |
| `project_id` | 项目名，`drama_<剧名>_<日期>` | 整部共用一个 |
| `shot_index` | 镜头序号，从 1 递增 | 不重复、不从 0 起 |
| `references` | 上一镜关键帧的 `local_path` | 第 1 镜留空 |
| `negative_prompt` | 不想要的元素 | 可选：`text, watermark, extra limbs` |
| `size` / `ratio` | 尺寸或比例 | 整部保持一致（如 `16:9`） |

请求示例：

```
ai_media_generate_image(
  prompt      = "CHAR_xiaolin_01 圆脸黑框眼镜右鬓翘发 白衬衫, 坐办公室双手抱头 焦虑, 黄昏暖光, cinematic",
  project_id  = "drama_斗破苍穹_20240315",
  shot_index  = 2,
  references  = ["<第1镜关键帧 results[0].local_path>"],
  ratio       = "16:9"
)
```

返回取 `results[0].local_path` —— 它既是「本镜视频的 `first_frame`」，也是「下一镜关键帧的 `references` 源」。

## 2. 视频：`ai_media_generate_video`

| 参数 | 说明 | 备注 |
|---|---|---|
| `prompt` | 首帧之后的运动与变化 | 只写变化，不复述外貌 |
| `first_frame` | 本镜关键帧的 `local_path` | 来自上一步 |
| `last_frame` | 下一镜关键帧的 `local_path` | 最后一镜可省略 |
| `references` | 角色参考图等 | 可选，强化一致 |
| `project_id` / `shot_index` | 与关键帧图同值 | 必须一致，才能按 `shot_index` 串起首尾帧链 |
| `duration` / `ratio` | 时长 / 比例 | 按 Provider 支持填 |

请求示例：

```
ai_media_generate_video(
  prompt      = "cinematic video, the man slowly lowers hands and looks up, warm light shifts, slow push-in",
  first_frame = "<第2镜关键帧 local_path>",
  last_frame  = "<第3镜关键帧 local_path>",
  project_id  = "drama_斗破苍穹_20240315",
  shot_index  = 2,
  duration    = 4,
  ratio       = "16:9"
)
```

## 3. 完整链路（一镜示范）

```
第1镜：generate_image(shot_index=1, references=[])        →  key1.png
第2镜：generate_image(shot_index=2, references=[key1])    →  key2.png
       generate_video(shot_index=2, first_frame=key2, last_frame=key3?)  ← key3 尚未生成时，先生成关键帧再回填
第3镜：generate_image(shot_index=3, references=[key2])    →  key3.png
       （回填第2镜 last_frame=key3，或第3镜视频 first_frame=key3）
```

> 实操顺序：先把所有关键帧图按 `shot_index` 串行生成完（每镜带上一镜 `references`），再逐镜补视频。这样每个视频的 `first_frame` / `last_frame` 都已就绪，首尾帧链一次接顺。

## 4. 返回字段

```
{
  "task_id": "...",
  "status": "succeeded",
  "results": [ { "local_path": "/.../ai_media/xxxx.png", "url": "https://...", "media_type": "image" } ]
}
```

- `results[0].local_path` 是垫图链 / 首尾帧链的关键，原样传给下一镜。
- `status`：`pending` / `running` / `succeeded` / `failed`；失败看 `error`，如实转述，别假装成功。

## 5. 回读项目（`ai_media_list_project`）

漫剧一跑就是十几二十镜，**不要靠上下文死记每镜的关键帧 `local_path`**。记错一个，首尾帧链就断了。随时调用它查回来：

```
ai_media_list_project(project_id = "drama_xxx_20240315")
```

返回 `shots[]`，按 `shot_index` 升序，每项含 `shot_index` / `kind` / `local_paths` / `prompt` / `status`。注意同一镜会同时有**关键帧图（image）和视频（video）**两条记录：

- 做第 N 镜视频时，`first_frame` 取**本镜 image 记录**的 `local_paths[0]`，`last_frame` 取第 N+1 镜关键帧（尚未生成就先生成它）。
- 做第 N 镜关键帧时，`references` 取**第 N-1 镜 image 记录**的 `local_paths[0]`。

`project_id` 留空则返回 `projects[]`，列出所有项目及其分镜数。

## 6. 错误处理

- **人物漂移**：后一镜 `references` 没传对上一镜 `local_path` → 核对关键帧链。
- **首尾帧接不上**：第 N 镜 `last_frame` ≠ 第 N+1 镜 `first_frame` → 回到分镜表核对首尾帧职责；不确定就调 `ai_media_list_project` 查实际产物。
- **一镜多运镜翻车**：prompt 里删到只剩一个主运镜。
- **连环失败**：通常根因是垫图链或首尾帧链断了，先修链再重生。

## 7. 命名项目、配音与成片

- **开工先建项目**：调 `ai_media_create_project({"project_id": "...", "name": "...", "kind": "video"})`，之后所有 `ai_media_generate_image` / `ai_media_generate_video` 都带同一个 `project_id`。
- **配音**：对需要台词的镜头调 `ai_media_generate_audio({"text": "...", "project_id": "...", "voice_id": "...", "speed": 1.0})`。走设备离线 TTS，零成本，产物以 `kind=audio` 落库；`voice_id` 留空用默认中文声音，`language` 可指定 BCP-47（如 `zh-CN`）。设备没装 TTS 或语言包会报错，如实转述。
- **成片拼装**：全部镜头生成完后调 `ai_media_compose({"project_id": "...", "format": "drama"})`。它按 `shot_index` 用 MediaMuxer 拼接视频；**各片段编码（分辨率/容器）必须一致**，否则拼接失败并回退为 `storyboard`（返回各片段 `segments` 本地路径清单），这时把清单交给用户，而不是假装拼出一段损坏视频。
