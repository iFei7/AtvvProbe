#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Murmur IME socket 协议参考客户端。

用于验证 App 侧的 socket 通道（也方便 native daemon 实现时对照）：
连接 → HELLO 鉴权 → CONTROL start → 实时送 16k PCM → CONTROL stop。

在盒子上用（需要 python3）：
    adb push test.pcm /data/local/tmp/
    adb shell python3 /data/local/tmp/murmur_sender.py --token <设置页里的 token> \
        --pcm /data/local/tmp/test.pcm

帧格式（与 App 内 PcmFrameCodec 一致）：
    type(1) + payload
    0x01 CONTROL  payload = cmd(1)                     1=start 0=stop 2=cancel
    0x02 PCM      payload = len(2, LE) + 裸 PCM
    0x03 HELLO    payload = len(2, LE) + token(utf-8)
    0x04 STATE    payload = state(1)
"""
import argparse
import os
import socket
import struct
import sys
import time

TYPE_CONTROL = 0x01
TYPE_PCM = 0x02
TYPE_HELLO = 0x03
TYPE_STATE = 0x04

CMD_STOP = 0
CMD_START = 1
CMD_CANCEL = 2

STATE_NAMES = {0: "IDLE", 1: "WAITING", 2: "RECORDING", 3: "FINISHING", 4: "REJECTED"}

FRAME_PCM = 3200          # 100ms @ 16k/mono/16bit
BYTES_PER_SECOND = 32000


def frame_control(cmd: int) -> bytes:
    return bytes([TYPE_CONTROL, cmd])


def frame_hello(token: str) -> bytes:
    body = token.encode("utf-8")
    return bytes([TYPE_HELLO]) + struct.pack("<H", len(body)) + body


def frame_pcm(data: bytes) -> bytes:
    return bytes([TYPE_PCM]) + struct.pack("<H", len(data)) + data


def connect(args) -> socket.socket:
    if args.tcp:
        s = socket.create_connection(("127.0.0.1", args.port), timeout=5)
        print(f"已连接 tcp 127.0.0.1:{args.port}")
    else:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        # Linux abstract namespace: 首字节为 \0
        s.settimeout(5)
        s.connect("\0" + args.name)
        print(f"已连接 unix abstract @{args.name}")
    s.settimeout(None)
    return s


def read_state(sock: socket.socket) -> None:
    """尽力读一个 STATE 帧（非阻塞式，读不到就算了）。"""
    sock.settimeout(1.0)
    try:
        data = sock.recv(8)
        if len(data) >= 2 and data[0] == TYPE_STATE:
            print("  <- STATE:", STATE_NAMES.get(data[1], data[1]))
    except (socket.timeout, OSError):
        pass
    finally:
        sock.settimeout(None)


def main() -> int:
    ap = argparse.ArgumentParser(description="Murmur IME socket 参考客户端")
    ap.add_argument("--name", default="murmur_ime", help="abstract socket 名字")
    ap.add_argument("--tcp", action="store_true", help="改用 TCP 127.0.0.1")
    ap.add_argument("--port", type=int, default=47821)
    ap.add_argument("--token", required=True, help="设置页里显示的握手 token")
    ap.add_argument("--pcm", help="裸 PCM 文件（16k/mono/16bit LE）")
    ap.add_argument("--seconds", type=float, default=0, help="不传 --pcm 时生成多少秒 1kHz 正弦")
    ap.add_argument("--realtime", action="store_true", default=True)
    args = ap.parse_args()

    sock = connect(args)
    sock.sendall(frame_hello(args.token))
    read_state(sock)

    sock.sendall(frame_control(CMD_START))
    read_state(sock)

    if args.pcm:
        raw = open(args.pcm, "rb").read()
    else:
        import math
        total = int(BYTES_PER_SECOND * (args.seconds or 3))
        samples = total // 2
        buf = bytearray()
        for i in range(samples):
            v = int(12000 * math.sin(2 * math.pi * 1000 * i / 16000))
            buf += struct.pack("<h", v)
        raw = bytes(buf)

    sent = 0
    t0 = time.time()
    while sent < len(raw):
        chunk = raw[sent:sent + FRAME_PCM]
        sock.sendall(frame_pcm(chunk))
        sent += len(chunk)
        if args.realtime:
            target = t0 + sent / BYTES_PER_SECOND
            delay = target - time.time()
            if delay > 0:
                time.sleep(delay)
    print(f"已送 {sent} 字节（{sent / BYTES_PER_SECOND:.2f}s 音频）")

    sock.sendall(frame_control(CMD_STOP))
    read_state(sock)
    time.sleep(0.5)
    sock.close()
    print("完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
