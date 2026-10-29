# DeepLine 2.3.0 — iMessage redesign (on top of the 2.2 glass UI)

Android package `com.deepline.privateapp` · versionName `2.3.0` (versionCode 16)

## AI Smart Assistant (new in this 2.3.0 build, versionCode 17)
Per-persona, opt-in phone assistant built on Android's **AccessibilityService** (no backend, no login, no API key in the APK — it uses the API key you already entered for that persona).

- **Enable**: persona edit page → bottom card **“启用 AI 智能助手”** → three confirmations in sequence (permissions & data flow → what it can / cannot do → risks & responsibility). Each needs a tick; *any* decline/dismiss cancels and nothing is enabled. Then Android asks you (system Accessibility settings) to switch on “DeepLine 智能助手”.
- **Use**: open that persona's chat → **+ → 助手** → describe the task. The assistant goes Home, then loops: read screen → ask the persona's AI for one action → safety gate → act → verify → repeat (max 25 steps).
- **Actions**: read screen text/controls, OCR (screenshot + on-device ML Kit, Android 11+), click / long-press / input text / scroll / tap / swipe, back/home/recents, open apps, open URLs, web search, wait, multi-step plans with result verification after every action (screen-changed check, foreground-app check, input read-back).
- **Controls**: floating panel over every app (status, step n/25, pause/resume, **stop**) + the same bar inside DeepLine. Stop cancels immediately, including a pending AI call.
- **Safety**: sensitive controls (发送/删除/发布/支付/购买/转账/安装/卸载 … or anything the AI flags) require “允许这一次” each time (60 s timeout = deny); never types into password fields; never touches system Settings, permission dialogs or installers; stops when the device is locked; never reads/acts when the service is off; http/https links only.
- **Disable/revoke any time**: “关闭 AI 智能助手” (per persona) and “撤销系统无障碍权限” (calls `disableSelf()`), or switch the service off in system settings.
- Screen text (and OCR text) is sent to the AI provider configured for that persona — this is disclosed in confirmation ①.
- Not available on Google Play without a policy exemption (Accessibility API use); intended for sideloaded builds.

## What changed in 2.3.0 (iMessage)
- **Chat page = iMessage**: back pill with unread count, centered avatar + name capsule (›), call button; blue bubbles (sent) / grey bubbles (received) with the curled tail on the last bubble of a run; no avatars beside bubbles; centered time separators; big emoji without a bubble; photos/GIFs without a frame; “20:55已读 / 已送达” receipt; rounded “iMessage信息” field with mic → blue send arrow.
- **Tapback**: long-press a bubble for ❤️ 👍 👎 😂 ‼️ ❓ + 复制 / 引用 / 删除. AI friends can tapback your messages too (they may start a reply with `[[REACT:❤️]]`, which the app turns into a reaction badge).
- **Dark / light iMessage**: Settings → 聊天外观 (自动 follows the wallpaper brightness; the dark look matches a dark wallpaper).
- **Start page = 信息**: large title, 编辑 + ✎ buttons, search with mic, 所有信息 / 群聊 pills, pinned AIs as round avatars, rows with blue unread dot, time and ›, floating glass tab bar (信息 · 动态 · 增加AI · 我的).
- **Small details kept/restored**: tap a bubble to show its time/status (and model · tokens for AI), 思考摘要, memory-summary hint, 编辑 mode (delete/pin), ✎ shortcut to add an AI.
- Defaults: title “信息”, input hint “iMessage信息”, sent bubble iMessage blue.

## What changed in 2.2.0
**Look & feel**
- New glass design system (`Glass.kt`): frosted translucent surfaces with a specular rim, silver “water” wallpaper (or your own photo from Settings), fading list edges, edge-to-edge layout.
- Chat page rebuilt after the reference: avatar + `···` capsule, “记忆自动总结已开启” pill, per-message avatars, glass capsule bubbles, `Nero ✓ 思考摘要 ⌃` header, `model ✦ tokens ✦ time` footer, glass composer (`+` · 说点什么 · expand · call/send).
- Home page rebuilt after the reference: 编辑 / 消息 / ⊕, search, 私聊·群聊 switch, conversation rows with verified badge, time and unread count, 4-tab bar **消息 · 动态 · 增加AI · 我的**.
- Safe on all devices: status/navigation-bar insets, keyboard (IME) insets, display cutouts, content width capped on tablets, font scale capped (0.85–1.15×), orientation changes no longer reset the app.

**Features**
- **动态** tab: post text/photo, like (toggle), comment, delete own posts; AI friends like/comment/reply, and can post on their own (random 30–60 min while the app runs) or on demand (“让 AI 发一条”).
- **增加AI** tab: import ZIP / create a character / create a group, plus your AI list.
- Bounce animations everywhere (press squish, springy entrances, tab pop, like pop, segmented thumb).
- New synthesized sound effects (send / receive / pop / success) in `res/raw`.
- Red envelopes and money transfers are kept (card redesigned; it no longer gets clipped by the bubble shape).
- “思考摘要” and token counts are shown only when the provider actually returns them (DeepSeek reasoning when 深度思考 is on; `usage` from the API).

**Bug fixes**
- New dynamics overwrote the newest existing one (`JSONArray.put(0, …)` replaces) → now prepended.
- Auto-post replaced the whole data object while chats were in flight (could lose replies) and never refreshed the UI.
- Whole chat JSON was written to disk every second by the coin timer → now every 30 s.
- Images were decoded at full size on the main thread → async, down-sampled, cached, EXIF-rotated.
- Edit button on the message list did nothing → real edit mode (delete with confirmation, pin).
- Import/export status messages were never visible → toast.
- System Back now leaves chat/editors instead of closing the app.
- Like button could be spammed → toggle.
- Several settings had no effect (bubble colours/shape/background image, home title, input hint) → wired up.
- Removed unused ML Kit dependency.

## Build
Open `android/` in Android Studio, or use the included GitHub Actions workflow (`gradle assembleDebug`).

## Verification status
**This archive was written without an Android SDK and has NOT been compiled or run on a device — including the assistant, which can only be tested on a real device/emulator with the accessibility service enabled.** Please build it and report any compile error or visual glitch.
