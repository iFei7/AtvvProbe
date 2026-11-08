package com.ifei7.murmur.ime.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ifei7.murmur.ime.util.Mlog
import java.util.ArrayDeque
import java.util.UUID

// ---------------------------------------------------------------------------
// 纯逻辑（无 Android 依赖，JUnit 覆盖）
// ---------------------------------------------------------------------------

/** Report Reference 描述符（0x2908）的解析结果：value = [reportId, type]，type 1=input 2=output 3=feature。 */
data class ReportReference(val reportId: Byte, val isOutput: Boolean) {
    companion object {
        fun parse(bytes: ByteArray?): ReportReference? {
            if (bytes == null || bytes.size < 2) return null
            val type = bytes[1].toInt()
            if (type !in 1..3) return null
            return ReportReference(bytes[0], type == 2)
        }
    }
}

/** 一条待写的 GATT 命令：目标 reportId + 负载（负载按 btsnoop 实测原样复现，含报告 ID 前缀）。 */
data class ReportWrite(val reportId: Byte, val payload: ByteArray)

/**
 * 「语音开始」命令序列的构造逻辑。
 *
 * 序列来自 bee 触发语音时的 btsnoop 实测写流量（见 tx6tmp/nanosic/NANOSIC_PROTOCOL_SPEC.md §0/§4）：
 *
 * ```
 * FA 输出报告: [01] → [FA 01] → [FA 00]     （对应 ATT handle 0x55）
 * F8 报告:     [01]                          （对应 ATT handle 0x51）
 * FB 输出报告: [01]                          （对应 ATT handle 0x4E）
 * ```
 */
object VoiceSequence {

    val REPORT_FA: Byte = 0xFA.toByte()
    val REPORT_F8: Byte = 0xF8.toByte()
    val REPORT_FB: Byte = 0xFB.toByte()

    /** FA/F8/FB 三个目标报告都找到才算映射完整，否则走 [buildFallback]。 */
    fun isComplete(reports: List<ReportReference>): Boolean {
        val ids = reports.map { it.reportId }.toSet()
        return REPORT_FA in ids && REPORT_F8 in ids && REPORT_FB in ids
    }

    /** 精确映射路径：只写 FA/F8/FB 三个目标报告。缺哪个就跳过对应条目。 */
    fun build(reports: List<ReportReference>): List<ReportWrite> {
        val seq = mutableListOf<ReportWrite>()
        if (reports.any { it.reportId == REPORT_FA }) {
            seq += ReportWrite(REPORT_FA, byteArrayOf(0x01))
            seq += ReportWrite(REPORT_FA, byteArrayOf(REPORT_FA, 0x01))
            seq += ReportWrite(REPORT_FA, byteArrayOf(REPORT_FA, 0x00))
        }
        if (reports.any { it.reportId == REPORT_F8 }) {
            seq += ReportWrite(REPORT_F8, byteArrayOf(0x01))
        }
        if (reports.any { it.reportId == REPORT_FB }) {
            seq += ReportWrite(REPORT_FB, byteArrayOf(0x01))
        }
        return seq
    }

    /**
     * 兜底路径：映射不完整（或精确路径有写失败）时，把触发序列套到每个候选报告上逐个试写。
     * 优先只试 output 报告；连 output 都没有时试全部。
     */
    fun buildFallback(reports: List<ReportReference>): List<ReportWrite> {
        val targets = reports.filter { it.isOutput }.ifEmpty { reports }
        val seq = mutableListOf<ReportWrite>()
        for (t in targets) {
            seq += ReportWrite(t.reportId, byteArrayOf(0x01))
            seq += ReportWrite(t.reportId, byteArrayOf(t.reportId, 0x01))
            seq += ReportWrite(t.reportId, byteArrayOf(t.reportId, 0x00))
        }
        return seq
    }
}

// ---------------------------------------------------------------------------
// GATT 编排
// ---------------------------------------------------------------------------

/**
 * 用标准 BLE API（BluetoothGatt）给已配对的 Nanosic 语音遥控器发送「语音开始」命令序列。
 *
 * 背景：ptrace 注入路径（h5acl → hci_h5_send_acl_data）把 com.android.bluetooth 打崩过 44 次、
 * 遥控器被搞僵；而遥控器的按键/心跳一直走标准 GATT 且正常，所以这里全程只用标准 API 复现触发序列。
 *
 * 流程：选已配对设备 → connectGatt(autoConnect=false, TRANSPORT_LE) → discoverServices →
 * 建立目标报告 → 特征 映射 → 按 [VoiceSequence] 逐条写，全程 logcat(tag RemoteTrigger)。
 *
 * 映射来源有两级：
 * 1. **ATT handle 直接映射（首选）**：Android 框架发现服务时把 ATT handle 存进
 *    characteristic.instanceId，与 btsnoop 实测的 handle（0x4E=FB / 0x51=F8 / 0x55=FA）一一对上，
 *    TX6 实测 8/8 命中。命中后**跳过描述符读取直接写**——实测这台遥控器的 GATT 服务器对
 *    第二个 GATT 客户端的 Read Request 不回包，挂起的读会阻塞后续写（writeCharacteristic 返回 false）。
 * 2. Report Reference 描述符（0x2908）读取（兜底）：handle 表没命中（其它型号遥控器）时才逐个读，
 *    单个 6s 超时，超时后放弃读取、用已知信息兜底试写。
 *
 * 线程模型：所有回调统一 post 回主线程；每步操作有超时保护。
 */
class RemoteVoiceTrigger(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** 每步进度（已在 logcat 打过一遍）。回调在主线程。 */
        fun onLog(line: String)

        /** 序列结束。success=false 表示中途失败。回调在主线程。 */
        fun onFinished(success: Boolean, summary: String)

        /** 按名字没匹配到唯一遥控器时回调全部已配对设备，由 UI 弹框选择后调 [start]。 */
        fun onNeedPickDevice(candidates: List<BluetoothDevice>)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var finished = false
    private var started = false

    /** Report Reference 描述符读取队列。 */
    private val refQueue = ArrayDeque<BluetoothGattDescriptor>()

    /** 当前在等回调的描述符（null = 没有在等；超时/回调后清空，防串扰）。 */
    private var waitingRef: BluetoothGattDescriptor? = null

    /** 解析成功的全部报告引用（按发现顺序）。 */
    private val refList = mutableListOf<ReportReference>()

    /** reportId → 特征（同一 reportId 出现多次时保留第一个）。 */
    private val charByReportId = mutableMapOf<Byte, BluetoothGattCharacteristic>()

    /** 待写命令队列。 */
    private val writeQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writeFailures = 0
    private var allowFallbackAfter = false
    private var fallbackUsed = false
    private var lastPayload: ByteArray? = null
    private var writing = false

    /** 正在写的一条（重试/等待回调期间保持，成功或放弃后才取下一条）。 */
    private var currentWrite: Pair<BluetoothGattCharacteristic, ByteArray>? = null

    private val stepTimeout = Runnable { onTimeout() }

    private fun armTimeout(ms: Long) {
        handler.removeCallbacks(stepTimeout)
        handler.postDelayed(stepTimeout, ms)
    }

    /** 读描述符超时 → 改用 ATT handle 映射继续；写超时 → 失败收尾。 */
    private fun onTimeout() {
        if (waitingRef != null) {
            waitingRef = null
            refQueue.clear()
            log("ReportRef 读取无回调（超时），改用 ATT handle 映射（instanceId 即 handle）")
            val g = gatt ?: return finishNow(false, "gatt 已失效")
            onRefsDone(g)
            return
        }
        if (writing) {
            finishNow(
                false,
                "写 ${lastPayload?.let { hex(it) }} 等应答超时（${TIMEOUT_MS / 1000}s）" +
                    "——遥控器空闲时固件睡眠，应答可能被扣住；请按键唤醒遥控器后重试"
            )
            return
        }
        finishNow(false, "等待 GATT 回调超时（${TIMEOUT_MS / 1000}s）")
    }

    private fun log(msg: String) {
        Mlog.trigger(msg)
        handler.post { listener.onLog(msg) }
    }

    /**
     * 开始执行。先按名字匹配已配对遥控器（含 "CMCC"/"Voice"）：
     * 唯一命中直接连；否则回调 [Listener.onNeedPickDevice] 让用户选，
     * 用户选择后带 [device] 再调一次。已经自动匹配过的情况下不能重复触发。
     */
    fun start(device: BluetoothDevice? = null) {
        if (finished) return
        if (started && device == null) return
        started = true
        val target = device ?: resolveRemote()
        if (target == null) return // 已回调 onNeedPickDevice 或已失败
        log("目标遥控器：${target.name ?: "?"} ${target.address}，连接（TRANSPORT_LE, autoConnect=false）…")
        armTimeout(TIMEOUT_MS)
        gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) finishNow(false, "connectGatt 失败")
    }

    /** 名字唯一命中返回该设备；多个/零个候选时回调选择框并返回 null。 */
    private fun resolveRemote(): BluetoothDevice? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            finishNow(false, "蓝牙不可用（未开启或没有适配器）")
            return null
        }
        val bonded = adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
        val matched = bonded.filter {
            val n = (it.name ?: "").uppercase()
            "CMCC" in n || "VOICE" in n
        }
        if (matched.size == 1) return matched[0]
        log(
            if (matched.isEmpty()) "没有名字含 CMCC/Voice 的已配对 BLE 设备"
            else "名字含 CMCC/Voice 的设备有 ${matched.size} 个，需要选择"
        )
        if (bonded.isEmpty()) {
            finishNow(false, "没有已配对的蓝牙设备")
        } else {
            bonded.forEach { log("已配对：${it.name ?: "无名"} ${it.address} type=${it.type}") }
            handler.post { listener.onNeedPickDevice(bonded) }
        }
        return null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (finished) return
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log("连接状态 = CONNECTED 但 status=$status（${statusName(status)}）")
                    }
                    log("已连接，开始发现服务…")
                    armTimeout(TIMEOUT_MS)
                    if (!g.discoverServices()) finishNow(false, "discoverServices 启动失败")
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    log("连接断开（status=$status ${statusName(status)}）")
                    finishNow(false, "连接断开 status=$status")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (finished) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishNow(false, "服务发现失败 status=$status（${statusName(status)}）")
                return
            }
            val services = g.services.filter { it.uuid == UUID_HID_SERVICE }
            if (services.isEmpty()) {
                finishNow(false, "没有 HID 服务（0x1812），服务数=${g.services.size}")
                return
            }
            mapByHandle(g)
            if (VoiceSequence.isComplete(refList)) {
                log("ATT handle 映射完整（FA/F8/FB 齐全），跳过描述符读取，直接写")
                startWrites(VoiceSequence.build(refList), allowFallbackAfter = true)
            } else {
                log("ATT handle 表未命中（可能不是 TX6 那颗遥控器），改读 Report Reference 描述符")
                for (svc in services) {
                    for (c in svc.characteristics) {
                        if (c.uuid != UUID_REPORT) continue
                        if (charByReportId.containsValue(c)) continue
                        val desc = c.getDescriptor(UUID_REPORT_REF)
                        if (desc == null) {
                            log("Report 特征 @${handleOf(c)} 无 0x2908 描述符，跳过")
                        } else {
                            refQueue += desc
                        }
                    }
                }
                if (refQueue.isEmpty()) {
                    log("没有可读的 Report Reference 描述符，走兜底试写")
                    startWrites(VoiceSequence.buildFallback(refList.distinctBy { it.reportId to it.isOutput }), allowFallbackAfter = false)
                    return
                }
                log("待读 Report Reference ${refQueue.size} 个")
                readNextRef(g)
            }
        }

        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (finished) return
            if (d !== waitingRef) return // 超时后的迟到回调，忽略
            waitingRef = null
            val value = d.value
            val ref = if (status == BluetoothGatt.GATT_SUCCESS) ReportReference.parse(value) else null
            val char = d.characteristic
            log(
                "ReportRef 读 @${handleOf(char)} status=$status（${statusName(status)}）" +
                    " value=${value?.let { hex(it) } ?: "null"} → ${ref ?: "解析失败"}"
            )
            if (ref != null) {
                refList += ref
                charByReportId.putIfAbsent(ref.reportId, char)
            }
            readNextRef(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (finished) return
            // 与 HID host 共享同一条 ACL/ATT 链路，系统已订阅的输入报告通知会一起送过来：
            // 0x51 = 按键/命令 ACK，0x4A = 音频（触发成功的关键判据）
            val v = c.value
            log("NOTIFY @${handleOf(c)} ${v?.let { hex(it) }}")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (finished) return
            writing = false
            currentWrite = null
            log(
                "写 @${handleOf(c)} value=${lastPayload?.let { hex(it) }} → " +
                    "status=$status（${statusName(status)}）"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) writeFailures++
            writeNext()
        }
    }

    // ---------------- 描述符读取 ----------------

    private fun readNextRef(g: BluetoothGatt) {
        val next = refQueue.pollFirst()
        if (next == null) {
            onRefsDone(g)
            return
        }
        waitingRef = next
        armTimeout(REF_TIMEOUT_MS)
        if (!g.readDescriptor(next)) {
            waitingRef = null
            finishNow(false, "readDescriptor 启动失败")
        }
    }

    private fun onRefsDone(g: BluetoothGatt) {
        if (!VoiceSequence.isComplete(refList)) {
            log("按 Report Reference 映射不全，补用 ATT handle 映射")
            mapByHandle(g)
        }
        val complete = VoiceSequence.isComplete(refList)
        val seq = if (complete) {
            log("映射完整（FA/F8/FB 齐全），走精确序列")
            VoiceSequence.build(refList)
        } else {
            log("映射不完整（缺 FA/F8/FB 之一），走兜底试写")
            VoiceSequence.buildFallback(refList.distinctBy { it.reportId to it.isOutput })
        }
        startWrites(seq, allowFallbackAfter = complete)
    }

    /** 打映射日志并开始写（队列落地 + 兜底标志设置）。 */
    private fun startWrites(seq: List<ReportWrite>, allowFallbackAfter: Boolean) {
        log(
            "reportId 映射：" + charByReportId.entries.joinToString("，") {
                "0x%02X → @%s".format(it.key, handleOf(it.value))
            }
        )
        if (seq.isEmpty()) {
            finishNow(false, "没有可写的命令")
            return
        }
        beginWrites(gatt ?: return finishNow(false, "gatt 已失效"), seq, allowFallbackAfter)
    }

    /**
     * 直接用 ATT handle 建映射：Android 框架发现服务时把 handle 存进 characteristic.instanceId，
     * 与 btsnoop / h5acl 日志里的 handle 一致。表来自 NANOSIC_PROTOCOL_SPEC.md §4（TX6 实测）。
     */
    private fun mapByHandle(g: BluetoothGatt) {
        var mapped = 0
        for (svc in g.services) {
            if (svc.uuid != UUID_HID_SERVICE) continue
            for (c in svc.characteristics) {
                if (c.uuid != UUID_REPORT) continue
                val known = KNOWN_REPORT_BY_HANDLE[c.instanceId]
                if (known != null) {
                    refList += known
                    charByReportId.putIfAbsent(known.reportId, c)
                    mapped++
                }
            }
        }
        log("按 ATT handle 映射：$mapped 个")
    }

    // ---------------- 写命令 ----------------

    private fun beginWrites(g: BluetoothGatt, seq: List<ReportWrite>, allowFallbackAfter: Boolean) {
        writeFailures = 0
        writeQueue.clear()
        for (step in seq) {
            val char = charByReportId[step.reportId]
            if (char == null) {
                log("找不到 reportId 0x%02X 对应的特征，跳过 %s".format(step.reportId, hex(step.payload)))
                continue
            }
            writeQueue += char to step.payload
        }
        if (writeQueue.isEmpty()) {
            finishNow(false, "命令序列里没有一条能落到已知特征上")
            return
        }
        log("开始写命令序列，共 ${writeQueue.size} 条")
        // 打印目标特征属性：PROPERTY_READ=0x02 / WRITE_NO_RESPONSE=0x04 / WRITE=0x08 / NOTIFY=0x10。
        // Android 9 的 writeCharacteristic 会本地检查属性与 getValue()，
        // 无写属性的特征（如 F8@0x51 是 READ|NOTIFY）根本发不出去。
        writeQueue.forEach { (char, payload) ->
            log("目标 @${handleOf(char)} properties=0x%02X permissions=0x%02X 值=%s"
                .format(char.properties, char.permissions, hex(payload)))
        }
        if (!allowFallbackAfter) fallbackUsed = true
        currentWrite = null
        writeNext()
    }

    private fun writeNext() {
        val g = gatt ?: return finishNow(false, "gatt 已失效")
        if (currentWrite == null) {
            currentWrite = writeQueue.pollFirst() ?: run {
                onWritesDone()
                return
            }
        }
        val (char, payload) = currentWrite!!
        lastPayload = payload
        // 关键：Android ≤28 的 writeCharacteristic 读的是 characteristic.getValue()，必须先 setValue
        char.value = payload
        // 本遥控器 ATT 应答"晚一拍"且睡眠时完全不回 —— DEFAULT 写会因首笔应答永不来而
        // 阻塞整个 GATT 事务队列（实测 25s 超时，即使按键唤醒也不回写应答）。
        // WRITE_TYPE_NO_RESPONSE 不需要应答，命令送达即执行——与 ptrace 背靠背灌帧等效但走标准协议。
        // 实测 @0x0055/@0x004E props=0x0E 含 WRITE_NO_RESPONSE（0x04），可安全切换；
        // @0x0051 props=0x12 无写属性会被本地属性检查拒绝，由 noWriteProps 分支跳过。
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writing = true
        armTimeout(TIMEOUT_MS)
        if (!g.writeCharacteristic(char)) {
            writing = false
            // 同步 false 的可能：无写属性 / value 没设上（已设）。无写属性换 WRITE_TYPE_NO_RESPONSE
            // 也过不了属性检查，直接放弃这条（实测 h5acl 能写 handle 0x51 是绕过了属性检查的裸注入）。
            val noWriteProps = char.properties and (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) == 0
            if (noWriteProps) {
                log("放弃 @${handleOf(char)}：特征无写属性（0x%02X），标准 API 无法向该 handle 发写".format(char.properties))
                writeFailures++
                currentWrite = null
                writeNext()
                return
            }
            finishNow(false, "writeCharacteristic 被栈拒绝（props=0x%02X）".format(char.properties))
            return
        }
        // 等待 onCharacteristicWrite 回调
    }

    private fun onWritesDone() {
        if (writeFailures > 0 && allowFallbackAfter && !fallbackUsed) {
            fallbackUsed = true
            log("精确路径有 ${writeFailures} 条写失败，进入兜底试写（每个候选报告试 [01]/[id 01]/[id 00]）")
            beginWrites(gatt ?: return, VoiceSequence.buildFallback(refList), allowFallbackAfter = false)
            return
        }
        finishNow(writeFailures == 0, "写序列完成，共 ${writeFailures} 条失败")
    }

    // ---------------- 收尾 ----------------

    fun cancel() = finishNow(false, "已取消")

    private fun finishNow(success: Boolean, summary: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(stepTimeout)
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        log(if (success) "结束：$summary" else "结束（失败）：$summary")
        handler.post { listener.onFinished(success, summary) }
    }

    // ---------------- 小工具 ----------------

    /**
     * 特征的 ATT handle（框架发现服务时把 handle 存进 instanceId），
     * 用于和 btsnoop / h5acl 日志里的 handle 直接对照。
     */
    private fun handleOf(c: BluetoothGattCharacteristic): String = "0x%04X".format(c.instanceId)

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private fun statusName(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
        0x01 -> "invalid handle"
        0x02 -> "read not permitted"
        0x03 -> "write not permitted"
        0x05 -> "insufficient authentication"
        0x06 -> "request not supported"
        0x07 -> "invalid offset"
        0x08 -> "connection timeout"
        0x0D -> "invalid attribute value length"
        133 -> "GATT_ERROR"
        else -> "status $status"
    }

    companion object {
        /**
         * 单笔写的应答超时。实测这台遥控器的 ATT 应答固定晚一拍（收到下一个请求才发出上一条应答，
         * 空闲时固件睡眠连应答都不发），所以给足余量；bluedroid 自身 30s ATT 超时内。
         */
        private const val TIMEOUT_MS = 25_000L

        /** 单个描述符读取的超时（这台遥控器上描述符读取会无回调卡死，早超时早切换方案）。 */
        private const val REF_TIMEOUT_MS = 6_000L

        /**
         * TX6 实测的 handle → reportId 表（NANOSIC_PROTOCOL_SPEC.md §4，bta_hh 实际路由反向确认）。
         * 仅在 Report Reference 描述符读不出来时兜底使用。
         */
        private val KNOWN_REPORT_BY_HANDLE = mapOf(
            0x3E to ReportReference(0x01.toByte(), isOutput = false),
            0x42 to ReportReference(0x02.toByte(), isOutput = false),
            0x46 to ReportReference(0x03.toByte(), isOutput = false),
            0x4A to ReportReference(0xFC.toByte(), isOutput = false),
            0x4E to ReportReference(0xFB.toByte(), isOutput = true),
            0x51 to ReportReference(0xF8.toByte(), isOutput = false),
            0x55 to ReportReference(0xFA.toByte(), isOutput = true),
            0x58 to ReportReference(0xF9.toByte(), isOutput = false),
        )

        /** HID over GATT 服务。 */
        val UUID_HID_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

        /** Report 特征。 */
        val UUID_REPORT: UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")

        /** Report Reference 描述符。 */
        val UUID_REPORT_REF: UUID = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")
    }
}
