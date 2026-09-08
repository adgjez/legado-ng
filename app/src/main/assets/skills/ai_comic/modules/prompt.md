# 提示词与画面规范（AI 漫画）

漫画只出静帧，提示词的全部压力集中在两件事：**第一格把角色和画风定死**，以及**之后每格只改该改的那一段**。与 AI 漫剧共享同一套角色圣经，不各写一套。

## 1. 分层五层（分层顺序固定）

```
[固定角色圣经前缀] + [动作与表情] + [环境与光影] + [构图与景别] + [风格与质量] + [负面排除]
```

示例：

```
CHAR_xiaolin_01（圆脸黑框眼镜右鬓翘发 白衬衫最上扣不扣 左手手环）,
猛地站起来 椅子后仰 瞳孔收缩 震惊,
教室午后阳光 窗边逆光 桌面反光,
中景 侧面平视 浅景深,
Japanese manga style, high contrast black and white ink, screentone shading, clean line art,
negative: deformed hands, extra limbs, mutated face, text, watermark
```

**构图与景别是漫画比漫剧多出的一层**，必须显式写：
- 景别：远景 / 全景 / 中景 / 近景 / 特写
- 视角：平视 / 俯视 / 仰视 / 鸟瞰 / 虫视

同一场戏里景别要有变化，连续 3 格同景别会让画面发闷。

## 2. 风格前缀（整组定死，一字不改）

第一格定死风格前缀，**后面每一格原样复制，一个字都不要改**：

| 风格 | 前缀要点 |
|---|---|
| 日系黑白漫画 | `Japanese manga style, high contrast black and white ink, screentone shading, clean line art` |
| 日系全彩 | `Japanese anime style, vibrant cel shading, clean line art, soft rim light` |
| 国漫 / 条漫 | `Chinese manhua style, full color, detailed background, dramatic lighting` |
| 水墨国风 | `Chinese ink wash painting style, xianxia, flowing brush strokes, muted palette` |
| 美漫 | `American comic style, bold outlines, flat color with heavy shadows, halftone` |

中途换风格 = 整组重做，不要抱着「就换个试试」的念头改前缀。

## 3. 垫图权重与改写纪律

- 后一格 prompt = 上一格 prompt，**只改「动作与表情 / 环境与光影 / 构图与景别」三段**；角色前缀和风格前缀原样保留。
- **不要把上一格的画面内容复述进 prompt**——垫图已经把画面带进去了，复述反而会放大漂移。
- **一次只改一个变量**。同时改动作 + 景别 + 光影，模型会自由发挥，一致性立刻崩。
- 出图和预期差太远时，**退回上一格重垫再重生当前格**，不要在当前格反复抽卡，越抽越偏。
- 抽卡：普通格 2–3 版，关键格（登场 / 高潮 / 反转）出 4–6 版让用户挑。

## 4. 常见翻车与修法

| 现象 | 原因 | 修法 |
|---|---|---|
| 脸变了 | 角色前缀被改写，或垫图链断了 | 回滚上一格重垫，前缀原样复制 |
| 服装配色跳 | 色板没写进角色前缀 | 把服装主色 + 辅助色写进前缀 |
| 画风在写实与二次元间跳 | 风格前缀中途被改 | 全组统一前缀，一格都不许改 |
| 手部崩 | 高频通病 | 手部出画 / 改近景避手 / 负面补 `deformed hands` |
| 画面出现糊字 | 让模型写字必翻车 | 图里不写字，台词靠后期叠 |
| 多主体串味 | 主体描述顺序乱 | 固定「先主角后配角」的顺序 |
| 背景每格都变 | 环境段写得太随意 | 把场景固定描述写成常量，逐格复用 |

## 5. 模型适配要点

| 模型 | 约束 |
|---|---|
| GPT Image / gpt-image-1 | 中文友好，指令跟随强，适合多主体复杂分镜 |
| 即梦 / 豆包 | 中文友好，出图快，风格偏商业插画 |
| 万相 / Qwen-Image | 中文友好，中文文字渲染相对好（确实需要字时才用它） |
| Seedream / FLUX | 写实与质感强，风格前缀要写得更具体 |
| Midjourney / SD 系 | 权重语法不同（`::` / `()`），负面权重需单独配 |

> 本引擎只需要你给出 `prompt` + `references` + `size` / `aspect_ratio`，具体参数映射由 Provider 配置决定，不要在 prompt 里堆「4K / 大师级 / 顶级画质」这类无效风格词。

## 6. 负面与合规

- 负面：`text, watermark, deformed hands, extra limbs, mutated face, blurry`。
- 图里不写文字；台词靠后期叠字。
- 不生成违法违规、涉政、血腥暴力、色情低俗、侵权（真实人物 / 品牌 / 影视角色）内容；发布时按平台要求加 AI 生成标识。
