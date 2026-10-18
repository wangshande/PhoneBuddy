# PhoneBuddy · 桌面伙伴

**让闲置安卓手机，重新有个位置。**

不用再买专用的陪伴硬件。给旧手机装一个软件，把它放在桌边，它就能成为一个会回应你的桌面伙伴：你忙你的，它翻翻书、画几笔；你看过去，它抬头招呼；你开口时，能看到声波和逐步出现的文字。

Your old phone. Your new companion. No dedicated companion hardware required.

[English](README.en.md) · [下载 Android 预览版](https://github.com/wangshande/PhoneBuddy/releases) · [观看 24 秒功能演示](docs/media/phonebuddy-features.mp4) · [参与开发](CONTRIBUTING.md)

[![PhoneBuddy：闲置手机成为桌面伙伴](docs/cover.png)](docs/media/phonebuddy-scene.mp4)

> 当前为可体验的开源预览版。核心陪伴、中文离线识别和基础待办可在本机运行；自由对话需要自行配置模型服务。全天听记默认关闭，连续 24 小时续航仍待实测。

## 旧手机，也可以成为你愿意留在桌上的东西

PhoneBuddy 想让已经拥有的手机承担新的角色。它不要求你先添一台专用机器人，也不只是放一张静态表情在屏幕上。

- **写代码、学习时**：你专注自己的事，它在旁边翻书、画画、玩泡泡或叠石子。
- **抬头歇一会儿时**：它会对你的朝向作出回应，用眼神、上浮和小动作招呼你。
- **临时想起一件事时**：看着它说“帮我记下明天买牛奶”，查看识别文字，确认后加入待办。
- **想留住一天里的片段时**：手动开启全天听记，在本机查看、查找最近 24 小时的文字记录。

不需要把所有功能都打开。只让它在桌边安静地陪着，也是一种用法。

## 先看看它怎样陪你

- [桌面使用场景](docs/media/README.md)：用户在电脑前写代码，手机在桌边陪伴和回应。
- [24 秒竖版功能片](docs/media/phonebuddy-features.mp4)：原生角色动画、注视回应和语音反馈。
- [发布页](https://github.com/wangshande/PhoneBuddy/releases)：安装包、配乐版和静音版视频。

视频由手机原生动画帧与演示素材剪辑。场景合成、脚本注视和语音测试事件均有标注，不用作端到端识别准确率证明。

## 五个伙伴，各有一点性格

![月光球、云团、小幽灵、星芽、水滴与应用图标](docs/characters.png)

| 伙伴 | 性格 |
| --- | --- |
| 月光球 | 安静温柔，陪你慢慢来 |
| 云团 | 软绵绵的，适合一起放空 |
| 小幽灵 | 有点害羞，被你发现时很开心 |
| 星芽 | 把小事也当成值得庆祝的事 |
| 水滴 | 好奇灵动，认真听你说 |

角色使用 Android 原生 Canvas 绘制。眨眼、目光、身体变形与道具联动；没人看它时保持清醒，有人看过来时收起自己的小活动回应。

## 开始体验

1. 从 [Releases](https://github.com/wangshande/PhoneBuddy/releases) 下载 `PhoneBuddy-preview.apk`，安装到手机。
2. 打开「桌面伙伴」，选一个伙伴，点击「进入桌面」。
3. 按提示允许摄像头和麦克风，把手机放稳，让前置摄像头朝向你。
4. 先看它的表情和动作，再试着说一句明确的记事指令。
5. 在「陪伴与语音设置」中按需选择全天模式、查看听记或配置外部服务。

**旧手机要求：** Android 8.0 及以上、ARM64 处理器；注视互动需要可用的前置摄像头，语音需要麦克风。当前 APK 只包含 ARM64 原生库，32 位设备不支持；尚未做大量旧机型兼容与耗电测试。

内置中文语音模型随 APK 分发，约 42 MB；首次启动会在本机解压，手机不需要另外下载语音包。预览安装包约 68 MB。

预览版包名为 `com.deskbuddy.design`，可与原版并存；目前需手动进入桌面，关闭了充电广播自动启动，以免两个版本同时唤起。

## 语音、全天陪伴与记忆

| 能力 | 当前行为 |
| --- | --- |
| 注视回应 | 本机人脸与头部朝向检测，触发表情和动作；属于近似注视，不是精确眼球追踪 |
| 说话反馈 | 真实麦克风音量波形、逐步出现的听写文字 |
| 中文离线识别 | 内置 Vosk 模型，无需配置云服务即可使用 |
| 基础语义与待办 | 明确记事指令先确认；普通聊天不自动变成待办 |
| 自由对话 | 可配置对话模型接口；未配置时使用本地规则回应，不是完整离线大模型 |
| 外部 ASR | 可自行配置 HTTPS 转录地址、密钥和模型；使用分段音频转录，更新速度取决于服务 |
| 全天陪伴 | 拔电后可保留桌面；电池上遵循系统息屏时间，充电时可保持屏幕 |
| 全天听记 | 手动启动麦克风前台服务，通知中可停止；与桌面共用录音流 |
| 24 小时文字记忆 | 本地时间线、查找、清空和过期清理；对话回忆检索需单独启用 |

“全天陪伴”和“全天听记”是独立选项。陪伴不要求持续录音；开启后台听记、摄像头或长时间亮屏都会增加耗电。当前尚未完成连续 24 小时耐久与电量基准测试。

## 数据留在哪里

- **摄像头画面在本机处理**，用于人脸/头部朝向检测，不上传画面。
- **默认中文识别在本机进行**；听记只保存文字，不把原始录音写入存储。
- **外部语音服务由你选择启用**；启用后，音频片段会发送到你配置的转录服务。
- **对话模型由你配置**；对话文字会发送给该服务。开启对话回忆后，回忆类问题可附带相关听记片段。
- 听记可以从通知停止，也可以在时间线中查看、查找或清空。应用被系统停止后，不会隐蔽地自行恢复录音。

## 给开发者

原生 Android / Kotlin，JDK 17，Gradle 8.7，Android SDK 34，build-tools 34.0.0。无需 Homebrew。

```sh
git clone https://github.com/wangshande/PhoneBuddy.git
cd PhoneBuddy
# 准备 JDK 17 与 Android SDK，并将 SDK 路径写入本机 local.properties
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。语音模型已随仓库包含，Gradle Wrapper 固定官方 SHA256；首次构建仍需要联网获取 Gradle 和 Maven 依赖。本机构建使用自己的 debug 签名，不保证能覆盖其他人构建的已安装包；请自行管理升级签名，不要提交密钥。

| 主要代码 | 职责 |
| --- | --- |
| `PetView.kt` | 角色外形、表情、自主活动与注视反馈 |
| `DeskActivity.kt` | 陪伴桌面、语音反馈和充电/断电状态 |
| `DeskService.kt` / `GazeGeometry.kt` | 本机摄像头分析与头部朝向判断 |
| `VoskAsr.kt` / `CloudAsr.kt` | 内置离线识别与可配置外部转录 |
| `Judge.kt` / `VoiceParser.kt` | 指令语义、简单回应与待办确认 |
| `MemoryService.kt` / `MemoryStore.kt` | 后台听记、最近 24 小时文字与检索 |

这些文件均位于 `app/src/main/java/com/deskbuddy/`。包名保留原项目的命名，以保持数据与构建兼容。

## 当前验证与下一步

已通过 **14 项 JVM 测试、20 项真机回归，Android lint 0 错误**。覆盖角色、摄像头帧、麦克风启停、中文录音样本、文字/声波、横竖屏、待办确认、全天模式断电事件和听记启停。测试包含受控事件，不能当作真实注视或远场识别的准确率基准。

接下来重点验证：不同旧机型的兼容性和耗电、真人摆放角度与噪声环境、外部 ASR 服务，以及连续 24 小时稳定性。背景噪声、方言与厂商省电策略仍可能影响体验。

具体复现方式、已知限制和动画素材导出入口见 [测试说明](docs/TESTING.md)。欢迎通过 [Issues](https://github.com/wangshande/PhoneBuddy/issues) 分享你的机型、复现步骤和想让伙伴学会的事情；提交代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源许可

应用源码采用 [MIT License](LICENSE)。Vosk small Chinese 0.22 模型按 Apache-2.0 分发，原始文件、许可和 SHA256 说明保留在 `app/src/main/assets/voice/`；其他第三方组件保留各自许可证。

[Vosk 模型来源](https://alphacephei.com/vosk/models) · [Android 麦克风前台服务说明](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
