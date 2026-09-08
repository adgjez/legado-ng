# 工具协议（AI 漫画）

漫画只用到 `ai_media_generate_image`。视频相关参数见 ai_drama 的 `protocol.md`，这里只讲图片。

## 1. 调用 `ai_media_generate_image`

逐格串行调用，后一格依赖前一格的结果，**不要并发**。

| 参数 | 说明 | 备注 |
|---|---|---|
| `prompt` | 本格的画面描述 | 只写这一格，不要复述整段剧情；前置统一的风格前缀 |
| `project_id` | 项目名，`comic_<主题>_<日期>` | 同一组漫画全程共用一个 |
| `shot_index` | 分镜序号，从 1 递增 | 不复用、不从 0 起 |
| `references` | 上一格的本地路径数组 | 第 1 格留空；之后带 `results[0].local_path` |
| `negative_prompt` | 不想要的元素 | 可选，如 `text, watermark, extra limbs` |
| `size` / `ratio` | 尺寸或比例 | 如 `1024x1024` 或 `16:9`，整组保持一致 |
| `quality` / `style` | 质量与风格档 | 按所选 Provider 支持填 |

请求示例（伪代码）：

```
ai_media_generate_image(
  prompt      = "close-up, 小林 圆脸黑框眼镜 右鬓翘发 白衬衫, 焦虑神情, 晨光, 居中构图",
  project_id  = "comic_斗破苍穹_20240315",
  shot_index  = 2,
  references  = ["<第1格返回的 results[0].local_path>"],
  ratio       = "16:9"
)
```

## 2. 返回里你最该关心的字段

```
{
  "task_id": "...",
  "status": "succeeded",
  "results": [
    {
      "local_path": "/.../ai_media/xxxx.png",   // ← 下一格的 references 源
      "url": "https://...",
      "media_type": "image"
    }
  ]
}
```

- **`results[0].local_path`** 是垫图链的关键：把它原样塞进下一格的 `references`。
- `status` 可能是 `pending` / `running` / `succeeded` / `failed`；失败要看 `error` 字段，如实告诉用户是哪一格、报了什么。

## 3. 风格前缀（开 group 第一格定死）

在每格 prompt 前加统一风格词，整组画风才稳。可选预设：

| 风格 | 前缀关键词 |
|---|---|
| 日系黑白 | `Japanese manga style, black and white, clean line art, screentone shading` |
| 日系彩色 | `colored manga style, vibrant colors, cel shading, clean line art` |
| 美式漫画 | `American comic book style, bold outlines, halftone dots, dramatic lighting` |
| 水彩绘本 | `watercolor illustration, soft colors, children's book style` |
| 中国水墨 | `Chinese ink wash painting, traditional brush style, elegant minimalist` |
| 国风彩漫 | `manhua style, full color, watercolor texture, ink-wash elements` |

图里**不要写文字**——台词靠气泡后期叠，不要逼模型画字（十次九糊）。

## 4. 回读项目（`ai_media_list_project`）

长链生成（8 格以上）时**不要靠上下文死记每一格的 `local_path`**。一旦记错或记丢，垫图链就断了。随时调用它查回来：

```
ai_media_list_project(project_id = "comic_斗破苍穹_20240315")
```

返回 `shots[]`，按 `shot_index` 升序，每项含 `shot_index` / `kind` / `local_paths` / `prompt` / `status`。取**当前要生成的上一格**的 `local_paths[0]` 作为 `references`。

`project_id` 留空则返回 `projects[]`，列出所有项目及其分镜数，用于确认该接哪个项目继续。

## 5. 错误处理

- **垫图链断**：后一格 `references` 没传对上一格 `local_path`，人物就跳变。先核对 `local_path` 是否真的来自上一格；不确定就调 `ai_media_list_project` 查。
- **某格不满意**：单独重生成那一格，复用 `shot_index`，垫图用它的前一格；不要整组重来。
- **Provider 报错**：如实转述错误，别假装成功。跨格连环失败通常根因是垫图链断了。

## 6. 命名项目与成片

- **开工先建项目**：调 `ai_media_create_project({"project_id": "...", "name": "...", "kind": "image"})`，之后所有 `ai_media_generate_image` 都带同一个 `project_id`。这样媒体库能按项目名分组、可改名、可整体删除，而不是只显示一串原始 id。
- **成片拼长图**：全部分镜生成完后调 `ai_media_compose({"project_id": "...", "format": "comic"})`。它按 `shot_index` 把图片竖排拼成一张长图并落库，返回 `results[0].local_path` 即成品。分镜有缺（解码失败）会直接报错，别假装拼成。
