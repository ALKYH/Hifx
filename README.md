# Hifx

**Hifx** 是一款面向 Android 平台的 HiFi 本地音乐播放器，内置完整的专业级音效引擎，支持从解码到输出的全链路无损音频传输。

[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## ✨ 功能概览

| 功能模块 | 说明 |
|---|---|
| 🎵 本地媒体库 | 扫描设备存储，支持 FLAC / WAV / MP3 / AAC |
| 🎛️ 参数化均衡器 | 8 段可调 EQ，支持频率、增益、Q 值独立设置，实时频响曲线 |
| 🌐 HRTF 双耳渲染 | 基于 SOFA 数据库的头相关传递函数，实现耳机 3D 空间音频 |
| 🔊 卷积混响 | 自定义 IR 文件，原生 C++ FFT 卷积，实时混响表计 |
| 🎤 人声分离 / 变调 | 中置声道分离 + ±24 半音变调，可调人声频段 |
| 🔌 USB DAC 直通 | USB Host 独占模式，ISO 等时传输，SoxR 高质量重采样 |
| 📊 顶栏可视化 | 电平表 / 模拟表 / 波形 / 频谱柱 四种模式 |
| 📳 触觉音频 | 低频能量驱动马达，模拟鼓击触感 |
| 🎶 歌词显示 | 逐行同步歌词，发光特效，可调字号 |
| ⏱️ 睡眠定时 | 定时停止播放，跨会话记忆播放队列 |

---

## 📸 界面预览

> 播放页 · 音效页 · 媒体库页 · 设置页

（截图待补充）

---

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更高版本
- NDK r25+（构建原生音频库）
- Android 7.0（API 24）及以上设备

### 构建步骤

```bash
git clone https://github.com/ALKYH/Hifx.git
cd Hifx
./gradlew assembleDebug
```

首次构建时 CMake 会自动编译 `hifxaudio` 原生库（包含 SoxR 重采样器、FFT 卷积引擎和 USB 直通后端）。

---

## 🏗️ 音频引擎架构

### 总体分层

```
┌─────────────────────────────────────────────────────┐
│                   UI 层 (Kotlin)                     │
│  MainActivity · PlayerActivity · EffectsFragment     │
│  SettingsFragment · 自定义 View (EqCurveView 等)     │
└───────────────────────┬─────────────────────────────┘
                        │ StateFlow
┌───────────────────────▼─────────────────────────────┐
│              AudioEngine  (Singleton)                │
│  媒体库扫描 · 播放队列管理 · 音效参数持久化           │
│  USB 设备探测 · 睡眠定时 · 会话恢复                  │
└────────────┬──────────────────────┬─────────────────┘
             │ 构建                  │ 控制
┌────────────▼──────────┐  ┌────────▼────────────────┐
│   ExoPlayer (Media3)  │  │  AudioPlaybackService    │
│  DefaultRenderersFactory  MediaSession / 通知栏     │
│  DefaultAudioSink      │  └────────────────────────┘
└────────────┬──────────┘
             │ AudioProcessor 链
┌────────────▼─────────────────────────────────────────┐
│              DSP 处理链（串行）                       │
│                                                       │
│  VocalIsolationProcessor   人声分离 / 变调            │
│         ↓                                             │
│  HrtfBinauralProcessor     HRTF 双耳空间渲染          │
│         ↓                                             │
│  ConvolutionReverbProcessor  卷积混响（FFT）          │
│         ↓                                             │
│  StereoUtilityProcessor    限幅·声像·单声道·相位·Crossfeed │
│         ↓                                             │
│  TopBarVisualizationProcessor  频谱 / 波形分析（旁路）│
│         ↓                                             │
│  HapticAudioProcessor      低频能量提取→触觉驱动      │
│         ↓                                             │
│  UsbHostPassthroughProcessor  PCM 镜像→USB DAC        │
└────────────┬─────────────────────────────────────────┘
             │ PCM 数据
┌────────────▼─────────────────────────────────────────┐
│           音频输出后端                                │
│  ① Android AudioTrack (系统混音器)                   │
│  ② USB Host Direct (ISO / Bulk，独占模式)            │
└──────────────────────────────────────────────────────┘
```

### 核心组件说明

#### `AudioEngine`（核心单例）
- 封装 ExoPlayer 生命周期，向 UI 层暴露四个 `StateFlow`：`libraryState` / `playbackState` / `effectsState` / `settingsState`
- 在 ExoPlayer 的 `DefaultRenderersFactory` 中注入自定义 `AudioProcessor` 链
- 监听 `AudioDeviceCallback`，在 USB DAC 插拔时自动切换输出路由并请求独占权限

#### `AudioPlaybackService`（前台服务）
- 维护 `MediaSessionCompat`，处理耳机按键、蓝牙媒体控制
- 订阅 `playbackState` 更新通知栏（专辑封面、进度、操控按钮）

---

## 🔊 无损音频链路详解

Hifx 围绕"零重采样、零混音、高精度"三个目标设计了完整的无损音频传输通路。

### 1. 解码层 — 32-bit Float 输出

```
源文件 (FLAC/WAV/PCM)
    ↓ ExoPlayer 硬件解码器 / FFmpeg 软解
    ↓ DefaultAudioSink（floatOutput = true）
PCM 32-bit Float @ 原始采样率
```

- 开启 **HiFi 模式** 后，`DefaultAudioSink` 的 `enableFloatOutput` 置为 `true`，解码结果以 32-bit float 格式送入处理链，避免中间量化损失。
- **Hi-Res API** 开关控制是否向系统申请高分辨率音频轨道（`AudioTrack` > 48 kHz / 32-bit）。

### 2. DSP 链 — 全程 PCM 处理

所有内置 `AudioProcessor` 均基于 ExoPlayer 的 `BaseAudioProcessor` 实现，在 `AudioSink` 内部串行执行，**不经过系统 AudioFX 框架的额外重采样**：

| 处理器 | 输入格式 | 处理内容 |
|---|---|---|
| `VocalIsolationProcessor` | PCM 16-bit Stereo | Biquad 带通滤波 + 双延迟线变调 |
| `HrtfBinauralProcessor` | PCM 16-bit Stereo | ITD/ILD + SOFA HRTF 卷积 + 房间早期反射 |
| `ConvolutionReverbProcessor` | PCM 16-bit Stereo | 分段 OLA-FFT 卷积（原生/Kotlin 双后端） |
| `StereoUtilityProcessor` | PCM 16-bit Stereo | 限幅 · 声像 · 单声道 · 相位翻转 · Crossfeed |
| `TopBarVisualizationProcessor` | PCM 16/32-bit | FFT 频谱 + RMS 电平（纯旁路，不修改数据） |
| `HapticAudioProcessor` | PCM 16/32-bit | 低频 RMS → 马达脉冲（纯旁路） |
| `UsbHostPassthroughProcessor` | PCM 16-bit Stereo | 镜像 PCM 字节流到 USB 后端 |

### 3. USB DAC 独占模式 — Bit-Perfect 直通

当检测到外接 USB DAC 时，Hifx 可绕过 Android 系统混音器，实现真正的 Bit-Perfect 输出：

```
PCM 字节流 (UsbHostPassthroughProcessor)
    ↓ NativeUsbResamplerEngine（SoxR HQ 重采样，如需格式转换）
    ↓ UsbPcmOutputBackend
    ├── ISO 等时传输（linux/usbdevice_fs ioctl）—— 最低延迟
    └── Bulk 传输（UsbDeviceConnection.bulkTransfer）—— 兼容模式
```

**SoxR 重采样算法选项**（可在设置中切换）：

| 档位 | 说明 |
|---|---|
| Nearest | 最近邻插值，延迟最低 |
| Linear | 线性插值，平衡质量与延迟 |
| Cubic | 三次插值，高质量 |
| SoxR HQ | SoxR 高质量模式，THD+N 最低 |

**BitPerfect 旁路逻辑**：

- 当 USB 独占模式激活且 SRC Bypass 保证标志为 `true` 时，`bitPerfectBypassActive = true`
- DSP 链（HRTF、混响、人声分离等）被完全跳过，仅保留可视化和触觉处理器
- 输出路由信息实时显示在播放页顶部信息栏（采样率 · 位深 · 路由名称 · DAC 特效主题）

### 4. 原生层（C++ / NDK）

| 文件 | 功能 |
|---|---|
| `native_resampler.cpp` | SoxR 封装，支持 Nearest/Linear/Cubic/SoxR-HQ 四种算法 |
| `native_convolution.cpp` | OLA-FFT 卷积核，包含 soft-saturation 防爆音保护 |
| `native_top_bar_visualizer.cpp` | 实时 FFT 频谱分析与电平计算 |
| `native_usb_backend.cpp` | USB ISO / Bulk 传输，JNI 桥接 `UsbPcmOutputBackend` |

原生库以 C++17 编译，通过 CMake 链接 `soxr`（静态库，`BUILD_SHARED_LIBS=OFF`）。

---

## 🎛️ 音效功能详解

### 均衡器
- 8 段参数化 EQ，频率 32 Hz–8 kHz，增益 ±12 dB，Q 值 0.1–10
- 内置预设：Flat · Bass Boost · Vocal · Treble Boost · V-Shape
- 支持自定义预设保存 / 重命名 / 删除
- `EqCurveView` 实时绘制频响曲线，支持触控拖拽调节频段

### HRTF 空间音频
- 内置 FreeField 补偿 HRTF 数据库（`.sofa` 格式）
- 可调参数：头部半径（70–110 mm）、混合比例、Crossfeed 量、外化程度
- 支持独立左右声道空间定位（`SpatialPadView` 二维坐标拖拽）
- 环绕声模式：Stereo / 5.1 / 7.1（含各声道增益独立调节）

### 卷积混响
- 支持导入任意 WAV 格式冲激响应（IR）文件
- 原生 C++ FFT 后端优先，自动回退 Kotlin 软件实现
- 实时混响表（百分比）反映当前混响强度

### 人声分离
- 基于 M/S 矩阵的中置声道提取
- 可调人声频段（低切 60–2000 Hz，高切 800–8000 Hz）
- 人声变调：±24 半音，基于双延迟线 Pitch Shifter 实现

---

## ⚙️ 设置选项

- **HiFi 模式**：禁用静音跳过，启用高精度解码路径
- **Hi-Res API**：申请 32-bit float / 高采样率 AudioTrack
- **首选采样率**：44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz
- **USB 独占模式**：插入 USB DAC 时自动请求独占权限
- **USB 重采样算法**：Nearest / Linear / Cubic / SoxR HQ
- **触觉音频**：低频鼓击触感，可调延迟补偿（±250 ms）
- **可视化模式**：电平表 / 模拟表 / 波形 / 频谱柱
- **主题**：跟随系统 / 浅色 / 深色；USB DAC 金色主题
- **媒体库文件夹**：指定扫描目录（SAF 持久权限）

---

## 🛠️ 技术栈

| 组件 | 版本 |
|---|---|
| AndroidX Media3 ExoPlayer | 1.3.1 |
| AndroidX Media (MediaSession) | 1.7.0 |
| Kotlin Coroutines / Flow | Kotlin 1.9.23 |
| Android Navigation Component | 2.6.0 |
| Material Components | 1.9.0 |
| SoxR（音频重采样） | bundled |
| NDK C++ / CMake | C++17 / 3.22.1+ |

---

## 📁 项目结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   ├── native_convolution.cpp       # FFT 卷积混响
│   ├── native_resampler.cpp         # SoxR 重采样器
│   ├── native_top_bar_visualizer.cpp # 顶栏频谱分析
│   ├── native_usb_backend.cpp       # USB ISO/Bulk 传输
│   └── third_party/soxr/            # SoxR 源码（静态链接）
├── java/com/example/hifx/
│   ├── audio/
│   │   ├── AudioEngine.kt           # 核心音频引擎单例
│   │   ├── AudioPlaybackService.kt  # 前台播放服务
│   │   ├── HrtfBinauralProcessor.kt # HRTF 双耳渲染
│   │   ├── ConvolutionReverbProcessor.kt # 卷积混响
│   │   ├── VocalIsolationProcessor.kt    # 人声分离
│   │   ├── StereoUtilityProcessor.kt     # 立体声工具
│   │   ├── TopBarVisualizationProcessor.kt # 可视化
│   │   ├── HapticAudioProcessor.kt       # 触觉音频
│   │   ├── UsbHostPassthroughProcessor.kt # USB 直通
│   │   └── NativeUsbResamplerEngine.kt   # 原生重采样桥接
│   ├── ui/
│   │   ├── EqCurveView.kt           # 均衡器频响曲线
│   │   ├── TopBarVisualizerView.kt  # 顶栏可视化
│   │   ├── SpatialPadView.kt        # 空间定位拖拽板
│   │   ├── KnobView.kt              # 旋钮控件
│   │   ├── LyricMaskTextView.kt     # 歌词高亮遮罩
│   │   └── WaveformScrubPreviewView.kt # 波形拖动预览
│   ├── MainActivity.kt
│   ├── PlayerActivity.kt
│   ├── EffectsFragment.kt
│   └── SettingFragment.kt
└── res/
```

---

## 🤝 参与贡献

欢迎提交 Issue 和 Pull Request。请确保：

1. 新功能保持零重采样 / 低延迟原则
2. 原生 C++ 代码遵循现有的 JNI 命名规范
3. DSP 处理器继承 `BaseAudioProcessor` 并在 `buildPlayer()` 中注入

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源。SoxR 库遵循其自身的 LGPL 2.1 许可证。
