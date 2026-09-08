# 提示词与视频规范（AI 漫剧）

漫剧的提示词分两半：**关键帧图**（定一致）和**视频镜头**（定运动与衔接）。两者用同一套角色圣经，不各写一套。

## 1. 关键帧图提示词（分层五层）

```
[固定角色圣经前缀] + [动作] + [环境与光影] + [风格与质量] + [负面排除]
```

示例：

```
CHAR_xiaolin_01（圆脸黑框眼镜右鬓翘发 白衬衫最上扣不扣 左手手环）,
坐在办公室电脑前双手抱头 焦虑,
黄昏暖光从窗外打入 屏幕冷光反脸,
cinematic live-action style, sharp focus, film grain,
negative: deformed hands, extra limbs, text, watermark
```

人物多视图可拆 5 主图 + 2 特写（正面 / 侧面 / 背面 / 全身 / 半身 + 脸部 / 手部）。

## 2. 视频提示词（首尾帧之内只写变化）

视频镜头有 `first_frame` / `last_frame` 兜底，prompt **只写首帧之后的运动与变化**，不要复述外貌和场景：

```
[风格类型]视频, [主体动作], [环境变化], [光影变化], [镜头运动], [氛围]
```

示例（接上镜关键帧）：

```
cinematic video, the man slowly lowers his hands and looks up at the screen,
warm window light shifts across his face, slow push-in, tense quiet atmosphere
```

**一镜一动词纪律**（违反必翻车）：推 / 拉 / 摇 / 移 / 跟 / 升 / 降，一镜只保留一个主运镜，其余删掉。布光固定机位镜不要加运镜。

## 3. 因果状态链（保连续）

每镜写清「初始状态 → 可见触发 → 有序动作 → 反应 → 可读终态」，逐人、逐关键道具写始态、变化、终态。先修断链再写镜头。第 N 镜的终态必须能接第 N+1 镜的始态——这就是首尾帧链的内容来源。

## 4. 抽卡策略

- 每个镜头出 4–6 版，关键镜（钩子 / 高潮 / 反转）出 6–8 版，让用户挑。
- 同一镜头多次生成若差异大，回退到关键帧图重垫，再重生视频。

## 5. 模型适配要点

| 模型 | 约束 |
|---|---|
| MiniMax H3 | 单段 ≤15s；21 号长镜需拆；中文友好 |
| 可灵 | 参数化运镜（推拉摇移升降 + 幅度）；无原生音频；剧烈运动易翻车，拆成两段 |
| 即梦 / 混元 | 中文友好；对运镜幅度有专门参数 |
| Seedance | 中文友好；会过滤真实人脸照片输入 |
| Vidu / Runway / Veo3 / Sora / Pika | 各有权重与时长档，按所选 Provider 支持填 `duration` / `ratio` |

> 本引擎只需你给出 `prompt` + `first_frame` + `last_frame` + `duration` + `ratio`，具体参数映射由 Provider 配置决定，不要在 prompt 里写「4K / 电影级 / 丝滑」这类风格废话。

## 6. 负面与合规

- 负面：`text, watermark, deformed hands, extra limbs, mutated face`。
- 图里不写文字；台词靠后期叠字幕。
- 不生成违法违规、涉政、血腥暴力、色情低俗、侵权（真实人物 / 品牌 / 影视角色）内容；发布按平台要求加 AI 生成标识。
