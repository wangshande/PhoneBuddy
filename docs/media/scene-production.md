# 场景宣传片制作说明

使用内置 imagegen 生成桌面背景，未使用 CLI/API 回退。背景为静态生成图。角色取自手机导出的原生动画，使用 Swift / AVFoundation 合成镜头、字幕和屏幕画面。

- 场景版：24 秒，1920×1080，24fps，H.264 视频、中文合成旁白与低音量配乐。
- 竖版：24 秒，1080×1920，24fps。
- 人物没有真实打字动作；镜头靠近手机表达用户把注意力转向伙伴。
- 注视与语音使用受控事件，非端到端实时识别实录。

## 最终背景提示词

Use case: ads-marketing. Asset type: landscape cinematic background plate for an open-source Android companion app promotional video, 16:9 composition, 1920x1080 if possible. Photorealistic natural editorial photograph, first-person seated programmer viewpoint at a real modest home desk, no face visible: two natural human hands in a charcoal sweatshirt resting on a compact keyboard at lower left, a large laptop displaying a realistic dark code editor on the left half, and a slightly older ordinary black Android phone upright in a simple desktop stand on the RIGHT half, close enough that its screen occupies about one quarter of image width and half of image height. The phone should face almost directly toward the camera, have a clearly rectangular portrait screen with very little perspective distortion, four screen corners visible, uniformly pale sage green (#DCE1D9) empty screen with NO UI, NO character, NO icons, no camera notch inside the screen; black physical bezel outside it, subtle realistic glass reflections only at edges. Laptop screen must show plausible fictional Kotlin code with syntax colors, no real company logos, no keys, no personal information. Quiet desk atmosphere: pale natural wood, ordinary coffee cup, small desk lamp, soft window light, restrained desaturated sage and charcoal palette. The feeling is an authentic programmer working and an existing spare phone keeping them company, not a luxury futuristic hardware ad. Leave clean modest negative space near upper left or upper center for short video captions. Camera from the user's eye level looking slightly down over hands, human perspective, not flat lay. Sharp phone and hands, gentle photographic depth of field, realistic materials and proportions. No added labels, no text outside code editor, no brand marks, no watermark. This will be a staged concept scene, with real native app animation composited into the blank phone screen later.
