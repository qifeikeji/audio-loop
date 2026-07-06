# 音频转发 (AudioLoop)

一个 Android 小应用：把手机 **3.5mm 耳机孔的音频输入**实时转发给已连接的**蓝牙耳机**播放。

界面很简单：两个圆角矩形状态卡片

- **3.5mm 音频输入**：检测到耳机孔有信号输入（插入带麦克风环的 TRRS 音频线）时变绿
- **蓝牙耳机**：检测到蓝牙耳机（A2DP/SCO）已连接时变绿

当 3.5mm 输入被检测到时，应用会自动在后台前台服务中启动音频采集并播放，系统会自动把音频路由到已连接的蓝牙耳机。

## 编译方式（完全通过 GitHub Actions，无需本地环境）

本仓库已经配置好 `.github/workflows/build.yml`：

1. 把本仓库推送到你自己的 GitHub 仓库（`git push`）。
2. GitHub Actions 会自动触发（push 到 `main`/`master`，或手动在 Actions 页面点击 `Run workflow`）。
3. 编译完成后，在对应的 workflow run 页面的 **Artifacts** 里下载 `audio-loop-debug-apk`，解压得到 `app-debug.apk`。
4. 把 apk 传到手机上安装（需要在手机设置里允许“安装未知来源应用”）。

不需要在本地安装 Android Studio / SDK / Gradle，所有编译都在 GitHub 的云端 Runner 上完成。

## 使用说明

1. 打开 App，授予“录音”权限（用于从耳机孔采集音频，这是系统 API 的强制要求，音频不会被上传或存储）。
2. 手机连接好蓝牙耳机。
3. 手机 3.5mm 孔插入音频输入线（音源设备如电脑、机顶盒等的音频输出接到手机的耳机孔）。
4. 两个卡片变绿后，蓝牙耳机里就能听到 3.5mm 输入的声音。
5. 顶部通知栏会常驻一条“音频转发服务”通知，用于保持后台前台服务存活；下拉可以看到当前状态。

## 重要的硬件限制说明（请务必了解）

Android 手机的 3.5mm 孔本质上是为**耳机+麦克风**设计的 TRRS 四段插孔，并不是标准意义上的 Line-in 输入。要让手机把耳机孔当作"外部音频输入"采集，通常需要满足：

- 使用的连接线必须能让外部音源信号进入**麦克风(MIC)那一路**触点（常见做法是用一根 3.5mm 公对公的四段音频线，把音源设备的耳机输出接到手机的耳机孔）。
- 部分手机（尤其是国产定制系统，如 MIUI）对这种“外接线麦”识别策略不完全相同，有的机型能正常识别为 `Wired Headset`（同时有输出和麦克风输入），有的机型可能识别不到麦克风电路，导致本 App 检测不到“3.5mm 音频输入”卡片变绿。
- 如果你的耳机孔插入音频线后，第一个卡片一直不变绿，通常是硬件/线材不支持麦克风环路识别，可以换一根四段（TRRS）音频线，或使用带麦克风环的音频转接线试试。

由于手机型号、MIUI 版本、线材千差万别，这部分**依赖具体硬件是否支持**，代码层面已经采用 Android 官方推荐的 `AudioManager` 设备检测 API（`AudioDeviceInfo.TYPE_WIRED_HEADSET`），这是系统层面能拿到的最准确的判断方式。

## 项目结构

```
audio-loop/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/audioloop/
│       │   ├── MainActivity.kt          # 界面：两个状态卡片
│       │   └── AudioBridgeService.kt    # 前台服务：设备检测 + 音频采集转发
│       └── res/                         # 布局、字符串、颜色、图标
├── build.gradle / settings.gradle / gradle.properties
└── .github/workflows/build.yml          # GitHub Actions 编译配置
```

## 技术要点

- **设备检测**：`AudioManager.registerAudioDeviceCallback` 监听音频设备增删，判断 `TYPE_WIRED_HEADSET`（耳机孔）与 `TYPE_BLUETOOTH_A2DP` / `TYPE_BLUETOOTH_SCO`（蓝牙耳机）是否存在。
- **音频转发**：`AudioRecord`（`MediaRecorder.AudioSource.MIC`）采集 PCM 数据，通过 `AudioTrack`（`USAGE_MEDIA`）播放，交由系统自动路由到当前默认输出设备（已连接蓝牙耳机时即为蓝牙耳机）。
- **后台存活**：前台服务 + 常驻通知，`foregroundServiceType="microphone"`。
- **目标环境**：`minSdk 26`，`targetSdk 30`（贴合 Android 11 / MIUI 12.5.5），全部界面文案为简体中文。
