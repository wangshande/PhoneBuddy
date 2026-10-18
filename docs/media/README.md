# PhoneBuddy 视频与素材

| 视频 | 内容 | 规格 |
| --- | --- | --- |
| [桌面使用场景](phonebuddy-scene.mp4) | 第一人称电脑桌：写代码时手机翻书，看向它时回应，说话时出现声波与文字 | 24 秒，1920×1080，24fps，含中文旁白与轻配乐 |
| [竖版功能演示](phonebuddy-features.mp4) | 手机原生动画、五种形象、语音反馈与闲置手机定位 | 24 秒，1080×1920，24fps，含配乐 |

视频文件保存在仓库内，可下载后播放，也可从 [Releases](https://github.com/wangshande/PhoneBuddy/releases) 获取。

## 制作方式与边界

桌面背景由内置 imagegen 生成，是场景合成演示，不是真人实拍；镜头缩放表现从代码编辑器看向桌边手机。人物双手来自静态场景图，不是实际打字录像。

角色动画来自 Android 原生 PetView 在连接手机上导出的动态帧。注视切换由脚本触发；手机屏幕布局为场景化呈现，语音波形取自本地回归录音的音量，文字逐步呈现包含测试事件。因此视频不代表端到端注视、实时识别或对话准确率。

旁白使用本机中文语音合成，包含功能讲解与“帮我记下明天买牛奶”的语音示例；配乐已降低音量。配乐为本地合成音符，未使用第三方歌曲。主应用的隐私、权限与耗电限制见主 README。

English: These are concept/composited demonstrations, not live-action footage. The desk image is generated, native character frames are captured on an Android phone, and gaze/speech behavior includes controlled test events. The clips are not recognition accuracy benchmarks.

[背景生成提示词](scene-production.md)
