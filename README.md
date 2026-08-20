# Nook Mobile (Android)

> 动物森友会「时刻音乐」沉浸时钟的 **Android / 安卓原生版**。

本项目是开源项目 **nook-desktop**（基于 Electron 的桌面应用）的 **Android 移植版**，由 **AI 辅助编程**生成、打磨而成（纯 Java 实现，无 Kotlin 源码）。

## 关于项目

原版 nook-desktop 用《动物森友会》每一小时的背景音乐当「整点播报 + 沉浸环境音」。本移植版将其完整复刻到 Android：整点自动切换对应小时音乐、雨声底噪、城镇主题曲报时、K.K. 曲库 等内容均在手机上原生运行。

### 功能特性

- **整点切歌**：5 秒轮询 + 精确闹钟兜底，整点播放对应小时主题音乐（14 个游戏模式 + K.K. + 随机）
- **雨声环境**：普通雨 / 游戏雨 / 无雷雨三档，摆钟模式下整点单播不循环
- **主题曲报时**：`chime.ogg` 音频精灵，16 音符编辑器（上 8 / 下 8 两行，适配竖屏，改动即试听）
- **K.K. 曲库**：193 首现场 / 广播版，自定义播放列表，周六自动切 K.K.
- **离线缓存**：增量下载（Last-Modified 比对）到本地，可批量预下载，无网可听
- **多语言**：中 / 英 / 西 / 德 / 意 / 法，应用内即时切换，默认中文
- **前台播放服务**：MediaSession 通知栏常驻，音频焦点丢失自动暂停

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 | 纯 Java（单模块 Gradle） |
| 构建 | Gradle 8.7 + Android Gradle Plugin 8.5.2 |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 26 |
| 音频 | androidx.media3 ExoPlayer（BGM / 雨声）|
| 网络 / JSON | OkHttp、Gson |
| UI | AppCompat + Material Components |

## 构建

```bash
# Windows
gradlew.bat assembleDebug

# 产出 APK
app/build/outputs/apk/debug/app-debug.apk
```

> 需要本机已配置 Android SDK（`local.properties` 中的 `sdk.dir`，该文件不入库）。

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