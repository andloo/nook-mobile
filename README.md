# Nook Mobile (Android)

> 动物森友会「时刻音乐」沉浸时钟的 **Android / 安卓原生版**。

本项目是开源项目 **nook-desktop**（基于 Electron 的桌面应用）的 **Android 移植版**，由 **AI 辅助编程**生成、打磨而成（纯 Java 实现，无 Kotlin 源码）。

## 关于项目

原版 nook-desktop 用《动物森友会》每一小时的背景音乐当「整点播报 + 沉浸环境音」。本移植版将其完整复刻到 Android：整点自动切换对应小时音乐、雨声底噪、城镇主题曲报时、K.K. 曲库 等内容均在手机上原生运行。

### 功能特性

- **整点切歌**：整点对齐定时器 + 精确闹钟兜底，整点播放对应小时主题音乐（14 个游戏模式 + K.K. + 随机）
- **雨声环境**：普通雨 / 游戏雨 / 无雷雨三档，摆钟模式下整点单播不循环
- **主题曲报时**：`chime.ogg` 音频精灵，16 音符编辑器（上 8 / 下 8 两行，适配竖屏，改动即试听）
- **K.K. 曲库**：193 首现场 / 广播版，自定义播放列表，周六自动切 K.K.
- **离线缓存**：增量下载（Last-Modified 比对）到本地，命中缓存 24 小时内免联网校验，可批量预下载，无网可听
- **多语言**：中 / 英 / 西 / 德 / 意 / 法，应用内即时切换，默认中文
- **前台播放服务**：MediaSession 通知栏 / 锁屏常驻，提供播放暂停按钮（与主界面按钮同一行为），音频焦点丢失自动暂停

### 省电与内存优化

- **整点检测**：对齐到「下一个整点 +1s」的单次定时器 + 精确闹钟兜底（不再固定 5s 轮询，唤醒降至 1 次/小时）
- **缓存校验**：本地音频 24h 内免联网校验，命中缓存零网络请求（TTL 过期后首次播放再比对 Last-Modified）
- **播放引擎**：ExoPlayer 精简预缓冲（15s/30s）；淡入淡出改由 ValueAnimator 驱动，去掉 2ms/5ms 主线程忙循环
- **暂停即休眠**：暂停时停止整点定时器与闹钟；进程被回收后残留的整点触发不再产生「僵尸」前台服务
- **内存**：多语言词典单例 + 按需加载、通知大图标缓存、试听 AudioTrack 复用、空闲工作线程 30s 回收

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 | 纯 Java（单模块 Gradle） |
| 构建 | Gradle 8.14.5（wrapper）+ Android Gradle Plugin 8.13.2 |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 26 / buildTools 35.0.0 |
| 音频 | androidx.media3 ExoPlayer（BGM / 雨声）|
| 网络 / JSON | OkHttp、Gson |
| UI | AppCompat + Material Components |

## 构建

环境要求：**JDK 17** + Android SDK（`local.properties` 中配置 `sdk.dir`，该文件不入库）。
Gradle 由 wrapper 自动下载（8.14.5），依赖仓库走腾讯云镜像 + google/mavenCentral（见 `settings.gradle`）。

```bash
# Windows — 调试包
gradlew.bat assembleDebug
# 产出 app/build/outputs/apk/debug/app-debug.apk

# Windows — 正式包（R8 混淆 + 资源压缩）
gradlew.bat assembleRelease
# 产出 app/build/outputs/apk/release/app-release.apk
```

> release 构建开启了 `minifyEnabled` + `shrinkResources`，混淆规则见 `app/proguard-rules.pro`
> （含 Gson `TypeToken` 泛型签名保留规则）；签名凭据由根目录 `key.properties` 提供（不入库），
> 缺失时 `assembleRelease` 无法完成签名。此前无签名凭据时可用
> `gradlew.bat :app:minifyReleaseWithR8` 单独验证混淆与资源压缩。

## 目录结构

```
app/src/main/java/com/nook/mobile/
├── alarm/      # 整点准时唤醒
├── audio/      # 播放引擎、主题曲精灵、试听合成
├── data/       # 设置、K.K. 曲库、下载缓存、Ogg 音频流抽取
├── domain/     # 游戏目录、小时解析、URL 构建、主题曲模型
├── service/    # 前台播放服务
└── ui/         # 主界面、设置、主题曲编辑器、K.K. 列表
app/src/main/assets/   # i18n 文案、kk.json、更新日志
```

## 致谢与许可

- 移植自开源项目 **nook-desktop**（须遵守其原始许可）
- 背景音乐 / 主题曲 / K.K. 曲目等资源版权归各版权所有者所有

> 本项目仅供本人学习与个人使用。