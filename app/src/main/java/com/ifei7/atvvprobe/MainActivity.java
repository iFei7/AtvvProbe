package com.ifei7.atvvprobe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ATVV (Google Voice over BLE) 探针：
 *  - 连接 CMCC_Voice_Remote (BLE)
 *  - refresh() 清 GATT 缓存后完整服务发现
 *  - 枚举全部服务/特征/描述符
 *  - 订阅所有 NOTIFY/INDICATE 特征
 *  - 向可写特征发 GET_CAPS：v0.4 (0A 00 01 00 01)，无效再发 v1.0 (0A 01 00 00 03)
 *  - 收到 START_SEARCH(0x08) 后回写 MIC_OPEN (0C 00 02)，收 AUDIO_START 后落盘音频
 *  - 松开语音键或 15s 超时后写 MIC_CLOSE (0D 00)
 */
public class MainActivity extends Activity {

    private static final String TAG = "ATVV";
    private static final String MAC = "0C:F5:33:DA:75:CE";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID ATVV_SERVICE = UUID.fromString("ab5e0001-5a21-4f05-bc7d-af01f617b664");
    private static final UUID ATVV_TX = UUID.fromString("ab5e0002-5a21-4f05-bc7d-af01f617b664");
    private static final UUID ATVV_AUDIO = UUID.fromString("ab5e0003-5a21-4f05-bc7d-af01f617b664");
    private static final UUID ATVV_CTL = UUID.fromString("ab5e0004-5a21-4f05-bc7d-af01f617b664");
    // CMCC 变体：16-bit vendor 服务 0xFD10
    private static final UUID TX_CMCC = UUID.fromString("0000fd11-0000-1000-8000-00805f9b34fb");
    private static final UUID RX_CMCC = UUID.fromString("0000fd12-0000-1000-8000-00805f9b34fb");
    private static final UUID HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final UUID REPREF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb");

    private static final byte[] GET_CAPS_V04 = {0x0A, 0x00, 0x01, 0x00, 0x01};
    private static final byte[] GET_CAPS_V10 = {0x0A, 0x01, 0x00, 0x00, 0x03};
    private static final byte[] MIC_OPEN_16K = {0x0C, 0x00, 0x02};
    private static final byte[] MIC_OPEN_8K = {0x0C, 0x00, 0x01};
    private static final byte[] MIC_CLOSE = {0x0D, 0x00};
    private static final byte[][] OUT_TEST_PAYLOADS = {
            {0x0C, 0x00, 0x02},
            {0x0C, 0x00, 0x01},
            {0x0C, 0x02},
            {0x06, 0x01, 0x00, 0x00},
            {0x01},
    };

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<Runnable> opQueue = new ArrayDeque<>();
    private boolean opBusy = false;
    private TextView tv;
    private ScrollView sc;
    private final StringBuilder text = new StringBuilder();

    private FileOutputStream audioOut;
    private File audioFile;
    private int audioBytes = 0;
    private int audioPackets = 0;
    private boolean micOpen = false;
    private boolean capsSeen = false;
    private String micOpenCharDesc = null;
    private int discoverCount = 0;
    private BluetoothGattCharacteristic outCharA;
    private BluetoothGattCharacteristic outCharB;
    private final ArrayList<BluetoothGattDescriptor> repRefList = new ArrayList<>();
    private int repRefIndex = 0;

    // ------- 操作队列：保证同一时刻只有一个 writeCharacteristic/writeDescriptor 在途 -------
    private void enqueue(Runnable r) {
        opQueue.add(r);
        drain();
    }

    private int opGen = 0;

    private void drain() {
        if (opBusy) return;
        Runnable r = opQueue.poll();
        if (r == null) return;
        opBusy = true;
        final int gen = ++opGen;
        r.run();
        // 看门狗：3 秒后若该操作仍未完成（回调丢失）则强制释放队列，绝不永久卡死
        ui.postDelayed(() -> {
            if (opBusy && opGen == gen) {
                log("WATCHDOG op timeout, force release");
                opDone();
            }
        }, 3000);
    }

    private void opDone() {
        opBusy = false;
        ui.post(this::drain);
    }

    private void enqueueWrite(BluetoothGattCharacteristic c, byte[] data) {
        if (c == null || gatt == null) { log("!! enqueueWrite: null target"); return; }
        enqueue(() -> {
            c.setValue(data);
            boolean ok = gatt.writeCharacteristic(c);
            log("write " + shortUuid(c.getUuid()) + " " + hex(data) + " queued=" + ok);
            if (!ok) opDone(); // 同步失败时无回调，需自行释放队列
        });
    }

    @SuppressLint("SetTextI18n")
    private void log(final String s) {
        Log.i(TAG, s);
        ui.post(() -> {
            text.append(s).append('\n');
            if (text.length() > 60000) text.delete(0, 20000);
            tv.setText(text);
            sc.post(() -> sc.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sc = new ScrollView(this);
        tv = new TextView(this);
        tv.setTextSize(12f);
        tv.setTextIsSelectable(true);
        sc.addView(tv);
        Button openMicBtn = new Button(this);
        openMicBtn.setText("OPEN MIC");
        openMicBtn.setOnClickListener(v -> openMicTest());
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(openMicBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        log("AtvvProbe start. target=" + MAC);
        File dir = getExternalFilesDir(null);
        audioFile = new File(dir, "voice_capture.bin");
        ui.postDelayed(this::connect, 1200);
    }

    private void connect() {
        BluetoothAdapter ad = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (ad == null || !ad.isEnabled()) {
            log("!! bluetooth off");
            return;
        }
        BluetoothDevice dev = ad.getRemoteDevice(MAC);
        log("connecting...");
        gatt = dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            log("conn: status=" + status + " state=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("connected");
                boolean mtuOk = g.requestMtu(247);
                log("requestMtu(247) -> " + mtuOk);
                if (!mtuOk) {
                    scheduleServiceDiscovery(g);
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("disconnected");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            log("MTU changed mtu=" + mtu + " status=" + status);
            scheduleServiceDiscovery(g);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("services discovered status=" + status);
            processServices(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            log("WRITE done uuid=" + shortUuid(c.getUuid()) + " status=" + status
                    + " data=" + hex(c.getValue()));
            opDone();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            log("DESCRIPTOR done " + shortUuid(d.getUuid()) + " status=" + status);
            opDone();
        }

        @Override
        public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // 仅记录结果；读取推进由 readNextReportRef 的超时兜底驱动（RTK 栈可能不回调），
            // 这里绝不触碰 opQueue，否则一次不回调就永久卡死整个队列
            if (d.getUuid().equals(REPREF)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    byte[] v = d.getValue();
                    int reportId = (v != null && v.length > 0) ? (v[0] & 0xFF) : -1;
                    int reportType = (v != null && v.length > 1) ? (v[1] & 0xFF) : -1;
                    log(String.format(Locale.US, "REPTR h=%d reportId=0x%02x reportType=0x%02x",
                            d.getCharacteristic().getInstanceId(), reportId, reportType));
                } else {
                    log("REPTR read fail status=" + status);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null || v.length == 0) return;
            String u = c.getUuid().toString().toLowerCase(Locale.US);
            if (u.equals(RX_CMCC.toString())) {
                log("RAW notify <- uuid=" + shortUuid(c.getUuid()) + " len=" + v.length + " hex=" + hex(v));
            }
            boolean control = u.equals(ATVV_CTL.toString())
                    || (u.equals(RX_CMCC.toString()) && v.length <= 8
                        && (v[0] & 0xFF) <= 0x0C);
            if (control) {
                handleControl(v, c);
            } else {
                handleAudio(v, c);
            }
        }
    };

    private boolean isAtvv(String u) {
        return u.equals(ATVV_SERVICE.toString()) || u.equals(ATVV_TX.toString())
                || u.equals(ATVV_AUDIO.toString()) || u.equals(ATVV_CTL.toString());
    }

    private void scheduleServiceDiscovery(final BluetoothGatt g) {
        ui.postDelayed(() -> {
            List<BluetoothGattService> svcs = g.getServices();
            if (svcs == null || svcs.isEmpty()) {
                log("cache empty, discovering...");
                g.discoverServices();
            } else {
                log("cached services: " + svcs.size());
                processServices(g);
            }
        }, 800);
    }

    private void processServices(BluetoothGatt g) {
        discoverCount++;
        List<BluetoothGattService> svcs = g.getServices();
        log("service count = " + svcs.size());
        dumpServices(g);
        boolean hasAtvv = false;
        UUID fd10 = UUID.fromString("0000fd10-0000-1000-8000-00805f9b34fb");
        for (BluetoothGattService s : svcs) {
            if (s.getUuid().equals(ATVV_SERVICE)) { hasAtvv = true; break; }
        }
        boolean hasFd10 = false;
        for (BluetoothGattService s : svcs) {
            if (s.getUuid().equals(fd10)) { hasFd10 = true; break; }
        }
        log("ATVV(AB5E) present: " + hasAtvv + " / CMCC 0xFD10 present: " + hasFd10);
        if (!hasAtvv && !hasFd10 && discoverCount < 2) {
            // 缓存里没有 ATVV：清缓存后断开重连，触发完整服务发现
            refreshCache(g);
            ui.postDelayed(() -> {
                g.disconnect();
                ui.postDelayed(() -> {
                    BluetoothAdapter ad = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                    BluetoothDevice dev = ad.getRemoteDevice(MAC);
                    gatt = dev.connectGatt(MainActivity.this, false, cb, BluetoothDevice.TRANSPORT_LE);
                }, 1000);
            }, 300);
            return;
        }
        setupAll(g);
    }

    private void handleControl(byte[] v, BluetoothGattCharacteristic c) {
        int cmd = v[0] & 0xFF;
        log("CTL notify <- " + hex(v) + " (cmd=0x" + Integer.toHexString(cmd) + ")");
        if (cmd == 0x0B) {
            capsSeen = true;
            log("  >> CAPS_RESP: codecs byte = " + hex(new byte[]{v.length > 3 ? v[3] : 0}));
        } else if (cmd == 0x08) {
            log("  >> START_SEARCH (voice key)");
            micOpen = false;
            enqueueMicOpen();
        } else if (cmd == 0x04) {
            log("  >> AUDIO_START");
            micOpen = true;
        } else if (cmd == 0x00) {
            log("  >> AUDIO_STOP");
            micOpen = false;
            enqueueMicClose();
        } else if (cmd == 0x0C) {
            log("  >> MIC_OPEN_ERROR -> retry 8kHz codec");
            enqueueWrite(txChar, MIC_OPEN_8K);
        }
    }

    private void enqueueMicOpen() {
        if (txChar == null) {
            log("!! no TX char, cannot MIC_OPEN");
            return;
        }
        enqueue(() -> {
            txChar.setValue(MIC_OPEN_16K);
            boolean ok = gatt.writeCharacteristic(txChar);
            log("MIC_OPEN(0x0C 00 02) -> " + micOpenCharDesc + " queued=" + ok);
            if (!ok) opDone();
        });
    }

    private void enqueueMicClose() {
        if (txChar == null || !capsSeen) return;
        enqueue(() -> {
            txChar.setValue(MIC_CLOSE);
            boolean ok = gatt.writeCharacteristic(txChar);
            log("MIC_CLOSE queued=" + ok);
            if (!ok) opDone();
        });
    }

    private void handleAudio(byte[] v, BluetoothGattCharacteristic c) {
        audioPackets++;
        audioBytes += v.length;
        if (audioOut != null) {
            try { audioOut.write(v); } catch (IOException ignored) {}
        }
        if (audioPackets <= 3 || audioPackets % 40 == 0) {
            log("AUDIO pkt#" + audioPackets + " len=" + v.length + " total=" + audioBytes
                    + " head=" + hex(v).substring(0, Math.min(28, v.length * 2)));
        }
    }

    private void refreshCache(BluetoothGatt g) {
        try {
            Method m = g.getClass().getMethod("refresh");
            boolean r = (Boolean) m.invoke(g);
            log("gatt.refresh() -> " + r);
        } catch (Exception e) {
            log("refresh failed: " + e);
        }
    }

    private void dumpServices(BluetoothGatt g) {
        for (BluetoothGattService s : g.getServices()) {
            String su = s.getUuid().toString().toLowerCase(Locale.US);
            log("SERVICE " + shortUuid(s.getUuid()) + (s.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY ? " (primary)" : " (incl)"));
            if (su.contains("ab5e")) log("  *** ATVV SERVICE FOUND ***");
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                StringBuilder props = new StringBuilder();
                if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) props.append("W");
                if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.append("R");
                if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) props.append("N");
                if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) props.append("I");
                log("  CHAR " + shortUuid(c.getUuid()) + " h=" + c.getInstanceId() + " props=" + props);
                for (BluetoothGattDescriptor d : c.getDescriptors()) {
                    log("    DESC " + shortUuid(d.getUuid()));
                }
            }
        }
    }

    private void setupAll(BluetoothGatt g) {
        log("=== setup ===");
        audioPackets = 0;
        audioBytes = 0;
        try { audioOut = new FileOutputStream(audioFile, false); } catch (IOException e) { log("audio file err: " + e); }

        BluetoothGattCharacteristic tx = null;
        BluetoothGattCharacteristic rx = null;
        outCharA = null;
        outCharB = null;
        for (BluetoothGattService s : g.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                UUID cu = c.getUuid();
                if (cu.equals(ATVV_TX) || cu.equals(TX_CMCC)) tx = c;
                if (cu.equals(ATVV_CTL) || cu.equals(ATVV_AUDIO) || cu.equals(RX_CMCC)) rx = c;
                // HID Report char (0x2A4D) 且 props=WR（无 N）的是 Output Report 特征：
                // 实测 dump 显示 handle 78 与 85 两个
                if (shortUuid(cu).equals("0x2a4d")) {
                    int pp = c.getProperties();
                    boolean wrOnly = (pp & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                            && (pp & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0;
                    if (wrOnly) {
                        if (outCharA == null) {
                            outCharA = c;
                            log("OUT char A h=" + c.getInstanceId());
                        } else if (outCharB == null) {
                            outCharB = c;
                            log("OUT char B h=" + c.getInstanceId());
                        }
                    }
                }
            }
        }
        if (outCharA == null) log("OUT char A missing");
        if (outCharB == null) log("OUT char B missing");
        if (rx != null) {
            enableNotify(rx);
        } else {
            log("!! no RX (CTL/AUDIO/0xFD12) char found");
        }
        txChar = tx;
        if (tx != null) {
            final BluetoothGattCharacteristic ftx = tx;
            micOpenCharDesc = shortUuid(tx.getUuid());
            log("TX char = " + micOpenCharDesc + ", GET_CAPS scheduled (v0.4 @+1500ms, v1.0 @+5500ms)");
            // CCCD 订阅完成后延迟 1500ms 发 v0.4；5500ms 仍未收到 CAPS_RESP 则发 v1.0
            ui.postDelayed(() -> {
                if (!capsSeen) {
                    enqueue(() -> {
                        ftx.setValue(GET_CAPS_V04);
                        boolean ok = gatt.writeCharacteristic(ftx);
                        log("GET_CAPS v0.4 sent queued=" + ok);
                        if (!ok) opDone(); // 同步失败无回调，必须释放队列
                    });
                }
                ui.postDelayed(() -> {
                    if (capsSeen) {
                        log("GET_CAPS v1.0 skipped (capsSeen)");
                    } else {
                        enqueue(() -> {
                            ftx.setValue(GET_CAPS_V10);
                            boolean ok = gatt.writeCharacteristic(ftx);
                            log("GET_CAPS v1.0 sent queued=" + ok);
                            if (!ok) opDone();
                        });
                    }
                }, 4000);
            }, 1500);
        } else {
            log("!! no TX char found (ATVV_TX / 0xFD11)");
        }
        // REPTR 读取已禁用：RTK 栈在系统 HID host 持链时 readDescriptor 大多失败且会
        // 占住 GATT 导致后续 writeCharacteristic 返回 false。h78/h85 输出特征已确认。
        log("=== setup done, press the VOICE KEY on the remote ===");
    }

    private void collectReportRefs(BluetoothGatt g) {
        repRefList.clear();
        repRefIndex = 0;
        for (BluetoothGattService s : g.getServices()) {
            boolean svcMatch = s.getUuid().equals(HID_SERVICE);
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                String cu = shortUuid(c.getUuid());
                boolean charMatch = cu.equals("0x00fa") || cu.equals("0x00fb") || cu.equals("0x00fc")
                        || cu.equals("0xfd11") || cu.equals("0xfd12");
                boolean propMatch = (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_READ)) != 0;
                if ((!svcMatch && !charMatch) || !propMatch) continue;
                BluetoothGattDescriptor d = c.getDescriptor(REPREF);
                if (d != null) repRefList.add(d);
            }
        }
        log("REPTR candidates: " + repRefList.size());
        readNextReportRef();
    }

    private void readNextReportRef() {
        if (gatt == null) return;
        if (repRefIndex >= repRefList.size()) {
            if (repRefIndex > 0) log("REPTR mapping done");
            return;
        }
        final BluetoothGattDescriptor d = repRefList.get(repRefIndex++);
        boolean ok = gatt.readDescriptor(d);
        log("REPTR read start idx=" + repRefIndex + " ok=" + ok);
        // 超时兜底：1500ms 后无论回调与否都继续下一个（不走 opQueue，不阻塞写队列）
        ui.postDelayed(this::readNextReportRef, 1500);
    }

    private void enableNotify(BluetoothGattCharacteristic c) {
        gatt.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { log("no CCCD for " + shortUuid(c.getUuid())); return; }
        enqueue(() -> {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(d);
            log("subscribe " + shortUuid(c.getUuid()) + " queued=" + ok);
        });
    }

    private static String shortUuid(UUID u) {
        String s = u.toString().toLowerCase(Locale.US);
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return "0x" + s.substring(4, 8);
        }
        return s;
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 屏蔽遥控器按键对 Activity 的影响，便于配合测试
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        // 语音键（HID usage 映射为 F5/keycode 63 或 VOICE_ASSIST）按下瞬间立即回开麦命令，
        // 复现原厂栈"收到 f8 按下事件 -> 立即回 MIC_OPEN"的时序
        if (keyCode == 63 || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_MUTE) {
            log("VOICE KEY press detected keycode=" + keyCode + " -> firing mic burst");
            openMicTest();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private long burstUntil = 0;

    private void openMicTest() {
        // 持续开火模式：60 秒内每 400ms 轮流向 0xFD11 / h78 / h85 写 MIC_OPEN 候选，
        // 避免毫秒级时序竞争——先启动循环，用户再按住语音键
        log("OPEN MIC: continuous burst for 60s (0xFD11 + h78 + h85)");
        burstUntil = System.currentTimeMillis() + 60000;
        burstStep(0);
    }

    private void burstStep(final int step) {
        if (gatt == null || System.currentTimeMillis() > burstUntil) {
            log("burst end");
            return;
        }
        int t = (step / 2) % 3; // 0=0xFD11 TX, 1=h78, 2=h85
        byte[] payload = (step % 2 == 0) ? MIC_OPEN_16K : MIC_OPEN_8K;
        BluetoothGattCharacteristic c = (t == 0) ? txChar : (t == 1 ? outCharA : outCharB);
        if (c != null) {
            c.setValue(payload);
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            boolean ok = gatt.writeCharacteristic(c);
            log(String.format(Locale.US, "BURST h=%d payload=%s ok=%b",
                    c.getInstanceId(), hex(payload), ok));
        } else {
            log("BURST skip target=" + t);
        }
        ui.postDelayed(() -> burstStep(step + 1), 400);
    }

    @Override
    protected void onDestroy() {
        try { if (audioOut != null) audioOut.close(); } catch (IOException ignored) {}
        if (gatt != null) { gatt.disconnect(); gatt.close(); }
        super.onDestroy();
    }
}
