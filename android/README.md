# Murmur 语音输入法（Android TV）

把外部送来的 **16kHz / 单声道 / Int16 PCM** 实时送上豆包 WSS ASR，识别结果通过标准输入法接口
`InputConnection.commitText()` 输入到当前焦点输入框。

面向腾讯极光 TX6（Allwinner H6 / Android 9 / SDK 28）这类遥控器电视盒子，**不需要触摸屏、不弹软键盘**。

---

## 1. 产品约束（硬性）

> **只有当输入法被激活、且当前输入框真的持有 `InputConnection` 时，按遥控器语音键才会启动语音输入。**

- 桌面、视频播放、设置列表等没有输入框的场景：按键不会有任何反应，也不会弹提示打扰。
- 判断依据是 `InputMethodService.onStartInput()` / `onFinishInput()` 维护的 `hasFocus` 标志 +
  `currentInputConnection != null` + **`EditorInfo.inputType != TYPE_NULL`**。
  电视桌面（`com.leslie.tvhome`）也会创建一个 `inputType=0` 的 `InputConnection`，只想要按键而不是文字输入，
  所以 `TYPE_NULL` 一律视为「没有输入框」（判定逻辑在 `core/FocusPolicy.kt`，有单测覆盖）。
- socket 控制通道同样受这条约束：daemon 发来 `START` 时若没有输入焦点，App 会回 `STATE_REJECTED` 并忽略。

---

## 2. 架构

```
┌────────────────────────────────────────────────────────────┐
│ MurmurImeService (InputMethodService)                      │
│                                                            │
│  onStartInput/onFinishInput ──► FocusPolicy (inputType≠0)  │
│  onKeyDown(语音键) ───────────┐                             │
│                               ├──► VoiceController          │
│  SocketPcmSource.onCommand ───┘      (状态机)               │
│      ▲ (只做触发/可选 PCM)                │                 │
│      │ unix abstract socket               │ 裸 PCM           │
│      │ @murmur_ime                        ▼                 │
│  root daemon (hidraw→触发)          DoubaoAsrClient         │
│                                      (OkHttp WebSocket)     │
│  AudioRecordSource (USB 麦克风) ─────────►  │ JSON 文本帧    │
│      ▲ 默认音频源                          ▼                │
│                                    latestText ──► commitText│
└────────────────────────────────────────────────────────────┘
```

状态机（`VoiceState`）：

```
IDLE ──触发──► CONNECTING ──WS open──► RECORDING ──停止──► FINISHING ──提交──► IDLE
                    │                       │                  │
                    └──── 失败/取消 ─────────┴──────────────────┘
```

- `FINISHING` 期间仍会收最后一帧 `result` / `finish`，最多等 `finishGraceMs`（默认 1200ms）后提交。
- 会话超过 60s 自动收尾，避免忘关。
- 识别结果为空时不提交任何字符。

---

## 3. 音频源抽象

接口在 `audio/PcmSource.kt`，统一产出 16k/mono/Int16LE 裸 PCM：

| 实现 | 用途 | 说明 |
|------|------|------|
| `AudioRecordSource` | **默认 / 主线** | 系统麦克风 / USB 麦克风，`VOICE_RECOGNITION` 源，16k/mono/16bit，缓冲区 ≥2×最小缓冲 |
| `SocketPcmSource` | 备选 | 从本地 Unix abstract socket 读 daemon 送来的 PCM，同一条连接承载控制命令 |
| `FilePcmSource` | 联调 | 按 100ms 一帧的实时速率回放一个 PCM 文件；`tail=true` 时可等待文件增长 |

遥控器音频解码在固件层被证否（编码器在 Nanosic WNF171 芯片 ROM 里），
所以**默认走 USB 麦克风**，这是目前唯一能端到端跑通的音频源。

切换方式：设置页 → ③ 音频源（存 SharedPreferences，下次会话生效）。默认 `MIC`。

文件源的选择顺序：设置页里配置的路径 → `/data/local/tmp/murmur.pcm`（存在且可读时用 tail 模式）
→ `files/murmur_test.pcm`。App 读 `/data/local/tmp` 需要文件 `chmod 644`。

---

## 4. socket 协议（v1）

默认 **Unix domain socket，abstract namespace，名字 `murmur_ime`**（设置页可切到 TCP `127.0.0.1:47821`）。

```
帧 = type(1 byte) + payload
0x01 CONTROL  payload = cmd(1)                 客户端 → App：1=start 0=stop 2=cancel
0x02 PCM      payload = len(2, LE) + 裸 PCM   客户端 → App
0x03 HELLO    payload = len(2, LE) + token    客户端 → App，连接后的第一帧
0x04 STATE    payload = state(1)               App → 客户端：0 IDLE / 1 WAITING / 2 RECORDING / 3 FINISHING / 4 REJECTED
```

native 连接方式（abstract namespace）：

```c
struct sockaddr_un addr;
memset(&addr, 0, sizeof(addr));
addr.sun_family = AF_UNIX;
addr.sun_path[0] = '\0';
strcpy(addr.sun_path + 1, "murmur_ime");
connect(fd, (struct sockaddr *)&addr, offsetof(struct sockaddr_un, sun_path) + 1 + strlen("murmur_ime"));
```

握手 token 在设置页 ⑤ 显示（首次启动随机生成，可一键重置）。参考客户端：`tools/murmur_sender.py`。

---

## 5. 触发方式（双路）

1. **IME 按键**：`onKeyDown` 里匹配 `triggerKeyCodes`，默认 `{219 VOICE_ASSIST, 136 F5, 91 MUTE, 79 HEADSETHOOK}`，
   设置页可改。每次遇到新键码都会打一条 `检测到键码 N（KEYCODE_...）` 日志，便于现场确认遥控器真实键码。
2. **socket 控制**：daemon 收到 hidraw 语音键事件（按下 `f8 82 03 01` / 松开 `f8 82 03 00`）后发 `start`/`stop`。

第一次按开始，再按一次停止（toggle）；`start` 后立刻 `stop`（<1 帧音频）会被服务端判定为空结果。

---

## 6. 豆包凭证

- 设置页「登录豆包」打开 `https://www.doubao.com/chat`，用户手动登录。
- 登录成功后 App 用 `CookieManager.getCookie()` 取全部 cookie（含 httpOnly 的 `sessionid`），
  用 `evaluateJavascript` 读 `localStorage['samantha_web_web_id']` 与 `localStorage['__tea_cache_tokens_497858']`。
- 全部写入应用私有 SharedPreferences；**不写日志、不进源码、不提交 git**。
- 没有 `addJavascriptInterface`，WebView 关闭 `allowFileAccess` / `allowContentAccess`。
- cookie 失效（`sid_guard` 30 天）时 WSS 会直接断开，表现为「连接失败」，重新登录即可。
- **登录时需要软键盘**：本输入法故意不提供软键盘，所以登录页右上角会提示「按 MENU 键切换成系统输入法」，
  切到 AOSP LatinIME 输入手机号 / 验证码，登录成功后再切回来即可（`ime set` 或系统设置里切换）。

WSS 参数见 `doubao-docs/doubao-wss-asr-spec.md`，URL 构造在 `net/DoubaoConfig.kt`。

---

## 7. 构建

本地没有 Android SDK，构建走 GitHub Actions（`.github/workflows/build-ime.yml`）：

```bash
# 推送 + 触发 + 等结果 + 下载 artifact
python build_ime.py
```

CI 步骤：`gradle test`（JUnit 单测，必须先过）→ `gradle assembleDebug` → 上传 `murmur-ime-apk`。

产物：`apk/app-debug.apk`（或 Actions 页面的 `murmur-ime-apk`）。

> debug 包用仓库里固定的 `app/murmur-debug.keystore`（口令 `android`，只是调试用）签名，
> 这样每次 CI 重新构建都能 `adb install -r` 覆盖安装，不会把本地登录态清掉。
> **不要用这个 keystore 发布正式包。**

---

## 8. 部署到设备

```bash
export MSYS_NO_PATHCONV=1     # Git Bash 下必须，否则 / 路径会被转义
adb connect 192.168.2.83:5555
adb install -r apk/app-debug.apk
adb shell ime enable com.ifei7.murmur.ime/.MurmurImeService
adb shell ime set com.ifei7.murmur.ime/.MurmurImeService
```

验证输入焦点与提交：

```bash
adb shell am start -a android.settings.SETTINGS
adb logcat -s MurmurIme:* DoubaoAsr:* MurmurSock:*
```

联调音频（不依赖 daemon）：

```bash
adb push test.wav /data/local/tmp/
adb shell run-as com.ifei7.murmur.ime cp /data/local/tmp/test.wav files/murmur_test.wav
# 设置页 → ④ 加载测试音频 → 自测识别
```

---

## 8.5 USB 麦克风方案（当前默认路线）

遥控器的音频编码在芯片 ROM 里、拿不到，所以音频源默认走盒子上的 USB 麦克风。

### 接线
盒子 USB Host 口插一个**免驱 UVC/USB Audio** 麦克风（不需要装 App，插上 `dumpsys audio` 能看到
`USB Device` 的输入设备即可）。可以先用 `adb shell dumpsys media.audio_flinger | grep -i usb` 确认。

### 预授权（TV 上不方便点系统弹窗，直接用 adb 给权限）
```bash
MSYS_NO_PATHCONV=1 adb shell pm grant com.ifei7.murmur.ime android.permission.RECORD_AUDIO
MSYS_NO_PATHCONV=1 adb shell dumpsys package com.ifei7.murmur.ime | grep RECORD_AUDIO
```
装/覆盖安装后权限会保留，只有卸载重装才需要重新 grant。

### 自检（不需要登录豆包）
设置页 → ③ 音频源选「麦克风」→ 点「麦克风自检（录 3 秒）」，对着麦克风正常说话：

| 结果 | 含义 |
|------|------|
| RMS < 200 | 几乎静音：麦克风没工作 / 系统输入被静音 / 选错了设备 |
| 200 ≤ RMS < 2000 | 有环境声，但太小：靠近一点或调大输入增益 |
| RMS ≥ 2000 | 正常，可以用了 |

同时会显示峰值和 dBFS，峰值长期贴着 32767 说明过载，稍微离远一点。

### 常见问题
- **一直 RMS≈0**：`adb shell dumpsys media.audio_policy | grep -A5 "Input"` 看输入设备是否被路由到 USB；
  或者 `adb shell media volume --show --stream 1 --get` 确认录音流没被静音。
- **自检能听到声音，但识别不出字**：先确认豆包已登录（设置页状态行），再用 ④ 的「自测识别」跑一段真实语音 wav 定位。
- **第一次按键没反应**：检查是否在输入框内（见第 1 节约束）；桌面/视频播放里按键是故意不响应的。
- **麦克风自检报「初始化失败」**：99% 是没授权，执行上面的 `pm grant`。

### 调试通道（仅 debug 包注册）

```bash
# 目标输入框（启动即聚焦，用遥控器/命令验证语音键）
adb shell am start -n com.ifei7.murmur.ime/.ui.TestTargetActivity

# 直接验证 commitText 是否真的进了输入框（绕过 ASR）
adb shell am broadcast -a com.ifei7.murmur.ime.DEBUG_COMMIT --es text "语音输入测试"

# 把 socket 切到 TCP，方便用 adb forward + PC 脚本验证帧协议
adb shell am broadcast -a com.ifei7.murmur.ime.DEBUG_SOCKET --es mode tcp
adb forward tcp:47821 tcp:47821
python test_socket_tcp.py <token>
```

---

## 8.6 录音导出与试听（定位「录到的声音不对」）

自检除了显示 RMS / 峰值，还会把 3 秒录音存成 WAV，可以拉到电脑上亲耳听，
直接判定「麦克风采到的到底是什么」：

**方式一：设置页自检**

设置页 → ③ 音频源 → 「麦克风自检（录 3 秒）」。录音自动保存为
`/sdcard/Android/data/com.ifei7.murmur.ime/files/murmur_selftest.wav`，
界面会显示「已保存: <路径> (<KB>KB)」。

**方式二：debug 广播（仅 debug 包，录的时候不用碰 UI）**

```bash
MSYS_NO_PATHCONV=1 adb shell am broadcast -a com.ifei7.murmur.ime.DEBUG_MIC_RECORD --ei seconds 5
```

录 N 秒（默认 5，上限 60）保存为 `.../files/murmur_debug.wav`，路径同时打在 logcat（`MurmurIme` tag）。

**拉取与试听**

```bash
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/com.ifei7.murmur.ime/files/murmur_debug.wav
```

**判读要点**

| 听到的内容 | 结论 |
|------|------|
| 声音清晰、音量正常 | 采集链路没问题，问题在识别侧（凭证 / 协议 / 音频格式） |
| 声音很小 | 输入增益低，靠近麦克风或调大增益 |
| 声音忽大忽小 / 断续 | TV 盒子丢帧或 AGC 抽风 |
| 全是噪声 / 哔哔声 | 采样率或格式不匹配（实际采样率 ≠ 16kHz） |
| 完全静音 | 麦克风没工作 / 输入被静音 / 路由到了别的设备 |

配套参数：`adb shell dumpsys media.audio_policy | grep -A5 "Input"` 看输入路由；
`adb shell media volume --show --stream 1 --get` 看录音流音量。

---

## 9. 目录结构

```
android/
├── app/src/main/java/com/ifei7/murmur/ime/
│   ├── MurmurImeService.kt      输入法主体（无软键盘、按键触发、commitText）
│   ├── MurmurPrefs.kt           配置 + 凭证存储
│   ├── core/
│   │   ├── VoiceController.kt   状态机编排（音频源 + ASR + 提交）
│   │   ├── VoiceState.kt        状态枚举
│   │   ├── FocusPolicy.kt       「有没有输入框」判定（TYPE_NULL 过滤）
│   │   ├── AudioLevel.kt        RMS / 峰值 / 自检判定
│   │   ├── PcmChunker.kt        PCM 定长分块
│   │   └── StatusNotifier.kt    Toast 状态浮层
│   ├── audio/
│   │   ├── PcmSource.kt         音频源接口
│   │   ├── SocketPcmSource.kt   socket 通道（控制 + PCM）
│   │   ├── FilePcmSource.kt     文件回放
│   │   ├── AudioRecordSource.kt 麦克风（默认音频源）
│   │   ├── MicProbe.kt          麦克风自检（录 3s 算 RMS）
│   │   ├── PcmFrameCodec.kt     帧编解码
│   │   └── WavReader.kt         wav 解析
│   ├── net/
│   │   ├── DoubaoConfig.kt      WSS URL 构造
│   │   ├── DoubaoCredentials.kt 凭证模型与解析
│   │   ├── DoubaoAsrClient.kt   OkHttp WebSocket 客户端
│   │   └── AsrMessageParser.kt  下行 JSON 解析
│   └── ui/
│       ├── SettingsActivity.kt  设置页
│       ├── LoginActivity.kt     豆包网页登录（MENU 键可切系统输入法）
│       ├── TestTargetActivity.kt 调试用目标输入框
│       └── SelfTestRunner.kt    不走 IME 的 ASR 自测
├── app/src/test/java/…          JUnit 单测
└── tools/murmur_sender.py       socket 协议参考客户端
```

---

## 10. 验收记录（TX6，Android 9 / SDK 28）

| 项目 | 结果 | 证据 |
|------|------|------|
| CI 测试 | 40 个 JUnit 用例全绿 | Actions `build-ime` job `Unit tests` success |
| CI 构建 | 产出 `app-debug.apk`（约 1.4MB） | `murmur-ime-apk` artifact |
| IME 可选 | `ime list -s` 出现、可 `ime set` | `settings get secure default_input_method` = `com.ifei7.murmur.ime/.MurmurImeService` |
| 无软键盘 | 输入框获焦时只出现 1dp 透明 IME 窗口 | 截图 `shot_commit_final.png` |
| commitText | 文字真的进了任意 App 的输入框 | 截图 + `DEBUG 提交文本: 13 字, 有焦点=true` |
| 桌面不触发 | 电视桌面 `inputType=0 可输入=false`，提交被忽略 | `onStartInput: pkg=com.leslie.tvhome inputType=0 可输入=false` / `有焦点=false` |
| socket 握手 | HELLO 通过，回 `STATE_IDLE` | `客户端已接入（握手通过）` |
| socket 拒绝 | 桌面发 START 被拒，回 `STATE_REJECTED` | `socket 收到 START，但当前无输入焦点，拒绝` |
| 按键可达 IME | 硬件键能进 `onKeyDown` | `检测到键码 3（KEYCODE_HOME）` |

尚未验证（依赖用户操作）：真实豆包登录 + 真实语音的端到端识别。

---

## 11. 已知限制与风险
| 风险 | 说明 | 缓解 |
|------|------|------|
| **遥控器音频不可解码** | 编码器在 Nanosic WNF171 芯片 ROM 里，Flash 固件无打包器/无 PCM 循环，9 种假设全部证否 | **默认走 USB 麦克风**；socket 通道保留，未来解码成功可直接启用 |
| 遥控器语音键键码未知 | 可能被系统先消费，`onKeyDown` 收不到 | 见第 5 节；日志里会打实测键码 |
| IME 窗口为 1dp 高 | 极少数 App 的 `adjustResize` 会轻微抖动 | 不影响输入，可后续改成 `TYPE_INPUT_METHOD` 悬浮窗 |
| 豆包协议易变 | 接口、参数、页面结构随时可能改 | 参数集中在 `DoubaoConfig`，凭证集中在 `CredentialParser` |
| cookie 有效期 | `sid_guard` 30 天，过期后 WSS 直接失败 | 提示重新登录；错误信息里区分「连接失败」 |
| WebView 能否拿到 httpOnly cookie | Android `CookieManager.getCookie()` 实测可返回 httpOnly cookie | 若某 ROM 不行，需退回「WebView 内注入 JS 拦截 WSS」方案 |
| 无 GMS / 旧 WebView | 盒子 WebView 版本较老，登录页可能渲染异常 | 已在 TX6 上实测（见验收记录） |
| 空音频 | 只按键不说话会得到空结果 | 空文本不提交 |

---

## 12. 安全

- 只有 `INTERNET`（必需）与 `RECORD_AUDIO`（仅麦克风模式，运行时申请）两个权限。
- 不申请悬浮窗、不申请外部存储、不开 `addJavascriptInterface`。
- 日志永不打印 cookie / token 原文，`DoubaoCredentials.describeForLog()` 只输出「有几项、有没有 sessionid」。
- socket 有握手 token，未鉴权连接 5 秒内断开。
