# nanoMuse 沙箱能力扩展与落地技术手册

本文档梳理了 nanoMuse 在移动端（基于 PRoot 的 Linux 沙箱）与物联网（IoT）环境下的功能扩展规划、底层架构接入范式及关键技术参考，方便后续逐步落地实现。

---

## 一、架构接入范式（两种开发路径）

在开发新功能前，首先根据功能属性选择对应的开发路径：

```
                    ┌───────────────────────────────┐
                    │       你想接入什么能力？       │
                    └───────────────┬───────────────┘
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
【路径 A：需要操作手机物理硬件/系统】               【路径 B：网络协议 / IoT / 云端服务】
  (红外、摄像头、震动、传感器、蓝牙等)                 (智能冰箱、Home Assistant、网络API)
           │                                                 │
  只需写普通的 Android 业务代码:                       连 Android App 都不用重新编译:
  1. 写一个 XxxOffloadHandler.kt (15~30行)           1. 在沙箱终端直接 `pip install <库>`
  2. 在 MinisApp.kt 注册 1 行命令                    2. 编写独立 Python 脚本或 CLI 工具
  (底层 PRoot C 语言自动完成系统调用劫持)             (直接运行在 Alpine Linux 环境中)
```

---

## 二、标准开发模板代码（路径 A 通用模版）

实现任何硬件控制命令，只需要以下两步：

### 步骤 1：创建 Handler 类（以振动器为例）
文件路径：`android/src/android/app/src/main/java/io/github/nanomuse/sandbox/VibrateOffloadHandler.kt`
```kotlin
package io.github.nanomuse.sandbox

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult

class VibrateOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // 读取命令行参数: android-vibrate <毫秒数>
        val durationMs = request.argv.getOrNull(1)?.toLongOrNull() ?: 300L
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
        
        return NativeOffloadResult(exitCode = 0, output = "OK: Vibrated for ${durationMs}ms\n")
    }
}
```

### 步骤 2：注册命令
文件路径：`android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt`
```kotlin
NativeOffloadServer.register("android-vibrate", io.github.nanomuse.sandbox.VibrateOffloadHandler(this))
```
*注：沙箱启动时，`PRootKernel` 会全自动在 `/usr/local/bin` 生成同名占位桩并处理所有的寄存器掉包，无需碰任何 C 语言代码。*

---

## 三、待实现功能清单库

### 维度 1：物理环境感知与交互（视听触）

| 功能名称 | CLI 设计示例 | 底层 API / 依赖 | 实现难度 | 典型 Agent 场景 |
|---|---|---|---|---|
| **摄像头拍照** (`android-camera`) | `android-camera photo --facing back --out /var/minis/attachments/snap.jpg` | Android `CameraX` / `CameraManager` | ⭐⭐ (中低) | 让大模型具备物理眼睛，看题、看冰箱食材、看环境。 |
| **手电筒/补光** (`android-torch`) | `android-torch on`<br>`android-torch off` | `CameraManager.setTorchMode` | ⭐ (极低) | 暗光环境下拍照前补光、夜间手电筒助手。 |
| **触觉震动** (`android-vibrate`) | `android-vibrate 500` | `Vibrator` / `VibratorManager` | ⭐ (极低) | 任务完成提醒、报警震动、心跳感知。 |
| **物理传感器** (`android-sensor`) | `android-sensor light`<br>`android-sensor step` | `SensorManager` (光线/计步/气压/重力) | ⭐⭐ (中低) | 检测环境变暗自动开灯、统计今日步数。 |
| **声音采集** (`android-mic`) | `android-mic record --seconds 5 --out /tmp/rec.wav` | `AudioRecord` (直接写 WAV) | ⭐⭐ (中低) | 本地 Whisper 离线听音辨位、环境噪音分析。 |

---

### 维度 2：物理世界家电控制（红外与短距无线）

| 功能名称 | CLI 设计示例 | 底层 API / 依赖 | 实现难度 | 典型 Agent 场景 |
|---|---|---|---|---|
| **红外遥控** (`android-ir`) | `android-ir transmit --freq 38000 --pattern "..."`<br>`android-ir ac --brand gree --temp 24` | `ConsumerIrManager.transmit` + Python 编码算法 | ⭐⭐ (中低) | **盘活无网老家电**：一句话语音遥控老空调、电视、投影仪。 |
| **蓝牙/BLE 交互** (`android-ble`) | `android-ble scan --timeout 5`<br>`android-ble write --mac XX:XX --uuid ...` | `BluetoothLeScanner` / `BluetoothGatt` | ⭐⭐⭐ (中等) | 扫描周围蓝牙设备，直连智能手环、智能灯泡。 |
| **NFC 读写** (`android-nfc`) | `android-nfc read`<br>`android-nfc write --text "..."` | `NfcAdapter` | ⭐⭐ (中低) | 读门禁卡数据、读写智能 NFC 贴纸。 |

---

### 维度 3：智能物联网家电生态（IoT 双向控制）

*此类功能走 **路径 B**，直接在沙箱 Python 环境中实现，无需修改 APK。*

| 功能名称 | 实现方案 / 依赖库 | 获取凭据途径 | 实现难度 | 典型 Agent 场景 |
|---|---|---|---|---|
| **米家/云米生态** | `pip install python-miio` | 局域网 Token (米家抓包提取) | ⭐ (极低) | 实时读取冰箱温度、开启速冻、控制空气净化器/扫地机。 |
| **美的/华凌生态** | `pip install msmart-ng` | 美的美居局域网 Key/Token | ⭐ (极低) | 美的空调、冰箱温控、除菌离子开关。 |
| **涂鸦全球生态** | `pip install tinytuya` | 涂鸦开发者平台 LocalKey | ⭐ (极低) | 控制全球成千上万贴牌涂鸦方案的智能插座与家电。 |
| **全屋智能中枢** | 直接调用 Home Assistant REST API | 本地 HA 长期访问令牌 (Token) | ⭐ (极低) | **一网打尽**：用统一接口联动全屋所有智能硬件。 |

---

### 维度 4：高阶屏幕交互与安全核验

| 功能名称 | CLI 设计示例 | 底层 API / 依赖 | 实现难度 | 典型 Agent 场景 |
|---|---|---|---|---|
| **动态录屏** (`android-screen-record`) | `android-screen-record --seconds 5 --out /tmp/ui.mp4` | `MediaProjection` API | ⭐⭐⭐ (中等) | 录制 5 秒短视频发给视频大模型，看懂复杂动态 UI。 |
| **全局悬浮窗 HUD** (`android-overlay`) | `android-overlay show --text "订票中..."`<br>`android-overlay dismiss` | `WindowManager` 悬浮窗 | ⭐⭐ (中低) | Agent 在后台执行任务时，在屏幕顶层挂出半透明进度条。 |
| **生物识别硬件锁** (`android-biometrics`) | `android-biometrics verify --reason "敏感操作确认"` | `BiometricPrompt` (指纹/人脸) | ⭐⭐ (中低) | 执行 `rm -rf` 或转账敏感操作前，强跳指纹弹窗要求用户物理确认。 |
| **系统分享直达** (`android-share`) | `android-share /var/minis/workspace/doc.pdf` | `Intent(ACTION_SEND)` | ⭐ (极低) | 跑出的 Python 报表直接唤起微信/网盘系统分享面板。 |

---

### 维度 5：移动计算保障与电信协议

| 功能名称 | CLI 设计示例 | 底层 API / 依赖 | 实现难度 | 典型 Agent 场景 |
|---|---|---|---|---|
| **后台防休眠锁** (`android-wakelock`) | `android-wakelock acquire --timeout 3600` | `PowerManager.newWakeLock` | ⭐ (极低) | 锁住 CPU，保证息屏状态下长耗时下载/计算不被打断。 |
| **硬件热管理监测** (`android-thermal`) | `android-thermal status` | `ThermalManager` | ⭐ (极低) | 检测到手机发烫时，自动暂停本地大模型或重度爬虫降温。 |
| **电信基带与 USSD** (`android-telephony`) | `android-ussd "*100#"`<br>`android-telephony cells` | `TelephonyManager` | ⭐⭐ (中低) | 免交互自动查话费、查 4G/5G 基站频段与信号质量。 |
| **系统级下载托管** (`android-download`) | `android-download start --url "..."` | `DownloadManager` | ⭐⭐ (中低) | 下载超大文件交由系统后台管理，断点续传且更省电。 |

---

### 维度 6：端侧算力释放与系统反哺

| 功能名称 | 实现机制 | 优势与效果 | 实现难度 |
|---|---|---|---|
| **端侧 NPU 离线推理** (`android-mlkit`) | 宿主集成 Google ML Kit，提供 `android-mlkit ocr / barcode` | **0 网络流量、0 API 费用**。端侧芯片毫秒级出 OCR 和二维码结果。 | ⭐⭐ (中低) |
| **沙箱挂载为系统盘** (`DocumentsProvider`) | 宿主实现 Android 原生 `DocumentsProvider` 接口 | 手机自带的“文件管理”App 侧边栏直接出现沙箱目录，互传文件如插 U 盘。 | ⭐⭐⭐ (中等) |

---

## 四、推荐实施路线图（建议攻坚优先级）

1. **第一阶段：快速建立信心（1 天）**
   - 实现 `android-vibrate`（验证完整的 Kotlin Handler + 注册流程）；
   - 在沙箱里安装 `python-miio` / `tinytuya`（验证纯沙箱 Python 控制智能家电）。
2. **第二阶段：打通物理视听与老家电（2~3 天）**
   - 实现 `android-torch` 和 `android-camera`（赋予 Agent 视觉采样能力）；
   - 实现 `android-ir`（配合开源红外库，实现手机红外开空调）。
3. **第三阶段：安全与后台体验强化（1 周）**
   - 实现 `android-biometrics`（为大模型高危操作加装指纹安全阀）；
   - 实现 `android-wakelock` 和 `android-overlay`（解决后台长任务休眠问题与状态展示）。
