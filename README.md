# Solium's Local Dream Frontend

一个独立的 Android 前端，用来驱动 [local-dream](https://github.com/xororz/local-dream) 的「受控模式」后端，并可以把手机本身变成一台局域网内的 Stable Diffusion WebUI 服务器。

前端与后端完全解耦：后端升级不需要重新编译本应用。

---

## 功能

### 创作台

- **结构化提示词**：标签以 chip 呈现，上半英文、下半中文翻译
  - 点标签加一层括号 `(tag)` / `((tag))`，点 `×` 减一层，减完即移除
  - 概览框只显示拼接结果，点击弹出完整编辑器
  - 从文本框粘贴带括号的提示词，权重会被正确解析回来
- **提示词标签库**：按分类浏览，点选即插入；已加入当前字段的标签高亮打勾
- **img2img**：选择底图 + 重绘强度
- **预设分辨率**：只提供后端支持的画布尺寸，避免生成失败
- **生成后自动超分**：可选，完成后自动送往放大器再返回
- **实时预览**：生成过程中显示逐步预览画面与进度
- **结果可点开**：相册式双指缩放 / 拖动 / 双击放大，并可直接保存到图库、送图生图、送超分

### 模型管理

- 列出主机上的模型（含 SDXL / CPU / 自定义标记）
- **手动**启动与关闭模型（卸载可释放手机内存）
- 模型预设提示词：为每个模型配置触发词，选中即自动填入

### 设备监控

- 置顶悬浮条显示 NPU / CPU / GPU 温度，并叠加生成进度
- 自动识别热区，排除 `cpu-hw-trip` 之类的**假温度节点**（这类节点恒定为跳闸阈值）
- 支持 [Shizuku](https://shizuku.rikka.app/) 提权读取受限节点（可选，不授权也能用）

### 历史

- 本地保存生成结果（PNG 无损；超过 2048px 的自动转 JPEG 95 以控制体积）
- 点击查看大图，可保存到系统图库、送图生图、送超分、套用提示词
- **长按进入多选**，批量保存 / 批量删除

### WebUI 服务器

把本机变成一个 **AUTOMATIC1111 兼容**的 HTTP 服务，局域网内的浏览器或其他 SD 客户端都能用它出图。

- 内置一个自包含的网页界面（深色 Gradio 风格，橙色强调色，顶部 tab 导航）
- 页面包含：文生图 / 图生图 / 提示词库 / 模型 / 设置

---

## 支持的 WebUI 接口

只实现后端**真正能兑现**的接口，其余（脚本、扩展、Embedding 训练、interrogate 等）全部去除。

| 接口 | 说明 |
| --- | --- |
| `GET /` | 内置网页界面 |
| `GET /localdream/tags` | 提示词库（本应用扩展，非 WebUI 规范） |
| `GET /localdream/state` | 主机连接状态（同上） |
| `GET /sdapi/v1/sd-models` | 模型列表 |
| `GET·POST /sdapi/v1/options` | 读取 / 切换模型 |
| `GET /sdapi/v1/samplers` | 采样器列表 |
| `GET /sdapi/v1/upscalers` | 放大器列表 |
| `GET /sdapi/v1/progress` | 生成进度与当前预览 |
| `POST /sdapi/v1/txt2img` | 文生图 |
| `POST /sdapi/v1/img2img` | 图生图 |
| `POST /sdapi/v1/extra-single-image` | 超分 |
| `POST /sdapi/v1/interrupt` | 中断当前生成 |
| `POST /sdapi/v1/unload-checkpoint` | 卸载模型 |
| `POST /sdapi/v1/reload-checkpoint` | 重新加载模型 |

---

## 架构

本应用是纯控制端，不包含任何推理代码。

```
┌─────────────────────────────┐
│  本应用（Android）           │
│                             │
│  创作台 ──┐                  │
│  历史   ──┼─► 控制层 ──┐     │
│  设置   ──┘            │     │
│                        │     │
│  WebUI 服务器 ◄────────┘     │
└────────┬────────────────────┘
         │
         │ ① 控制口 8808（JSON）  ② 生成口 8081（SSE / 二进制）
         ▼
┌─────────────────────────────┐
│  local-dream 受控模式后端    │
│  （本机或局域网内其它设备）   │
└─────────────────────────────┘
```

反向也成立：局域网内的浏览器经 **WebUI 服务器** 进来，请求被翻译成 local-dream 协议转发出去，图片再按 WebUI 格式推回前端。

### 关键协议

**控制口（默认 8808）**

- `GET /info` — 主机身份（唯一免鉴权接口）
- `GET /models` — 模型与放大器目录
- `POST /select` — 启动指定模型（`{"model_id","width","height"}`）
- `GET /status` — 后端状态（`idle` / `starting` / `running` / `error`）
- `POST /stop` — 关闭后端

**生成口（默认 8081）**

- `GET /health`
- `POST /tokenize` — 提示词分词计数
- `POST /generate` — SSE 流式生成，逐行 `data: {json}`，以 `data: [DONE]` 结束
- `POST /upscale` — 原始 RGB 进、JPEG 出，权重路径走 `X-Upscaler-Path` 头

### 一个重要的工程细节

local-dream 的主机端 [把控制口做成了单 worker 线程](https://github.com/xororz/local-dream)，以保证 `select` / `stop` 的顺序性。这意味着**客户端轮询过猛会把队列打爆**。

本应用为此做了专门的退让策略：

| 场景 | 轮询间隔 |
| --- | --- |
| 空闲 | 5 秒 |
| 生成中 | 15 秒 |
| 连续失败 | 退避至最长 30 秒 |
| 模型加载中 | 主轮询暂停，由加载循环独占 |

状态查询还使用独立的**短超时**（8 秒）而不是长超时，遇到慢响应就快速失败、稍后重试，避免长时间占着那一格队列。

---

## 构建

### 环境

- JDK 17
- Android SDK（`compileSdk 37` / `minSdk 28` / `targetSdk 36`）
- Gradle 由 wrapper 提供

### 命令

```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

产物位置：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

> Release 目前使用 debug 签名，便于本地安装与迭代。正式分发请替换为自己的 keystore。

### 依赖

Kotlin + Jetpack Compose（Material 3）、OkHttp、DataStore、Coil，以及可选的 Shizuku（用于设备监控提权）。

---

## 使用

1. 在主机上运行 local-dream，并开启**受控模式**
2. 打开本应用，点右上角状态条填写主机地址与端口（本机即 `127.0.0.1:8808`）
3. 到「模型」页选中模型，点「启动所选模型」，等待就绪
4. 回「创作」页，从提示词库点选或直接编辑提示词，点「生成」

### 启用 WebUI 服务器

1. 设置 → **WebUI 服务器** → 打开开关
2. 复制页面上列出的局域网地址
3. 在同网段的电脑浏览器中打开，例如 `http://192.168.1.10:7860`

> 默认端口 `7860`（与 WebUI 一致），可自行修改。**默认关闭**——开启等于把出图能力开放给整个局域网。

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 访问后端与提供 WebUI 服务 |
| `ACCESS_NETWORK_STATE` | 检测局域网地址 |
| `moe.shizuku.manager.permission.API_V23` | 可选，经 Shizuku 读取受限的温控 / GPU 节点 |

应用**不需要**存储权限：保存到图库走 MediaStore，导入提示词走系统文件选择器。

---

## 目录结构

```
app/src/main/java/io/github/localdream/frontend/
├── data/           连接、提示词库、模型预设、历史、日志、设备监控
├── remote/         local-dream 协议客户端（控制口 / 生成口 / SSE）
├── webui/          WebUI 兼容服务器与内置网页
└── ui/             Compose 界面
    ├── components/ 提示词编辑、标签选择、温度条、图片查看器
    └── screens/    创作台、模型、历史、设置及其子页
```

---

## 相关项目

- [xororz/local-dream](https://github.com/xororz/local-dream) — 后端本体（Android 本地推理）
- [AUTOMATIC1111/stable-diffusion-webui](https://github.com/AUTOMATIC1111/stable-diffusion-webui) — WebUI 接口规范参考

## 许可证

仅供个人学习与使用。请遵守所连接后端项目自身的许可条款。
