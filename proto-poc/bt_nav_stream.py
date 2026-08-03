#!/usr/bin/env python3
"""Continuous BLE nav-stream test.

Hypothesis: the dash discards single-shot nav messages and only renders if
it sees a continuous stream that mimics an actively-navigating phone. The
OEM gates outbound nav on SQNavigationManage.getPageAction().isInNaving()
which stays true for the duration of the trip — so the dash gets a 1Hz
stream, not a one-shot.

Strategy:
1. Connect BLE, subscribe notifies, hold connection.
2. Send minimal handshake.
3. Stream msg_id 27 NAVI act=3 once per second for 60+ seconds with
   decreasing distance to simulate approach to a turn.
4. ALSO send msg_id 1 legacy each second (some firmware listens to one or
   the other).

Both forms simultaneously costs nothing — dash drops the one it doesn't
understand. Watch dash for any visible nav UI.

If continuous stream causes a render where single-shot didn't, that's the
gate. If still nothing, the dash's nav-render feature may need an additional
explicit "I am in nav mode" indicator we haven't found yet.
"""
import asyncio
import json
import struct
import sys
from datetime import datetime
from bleak import BleakClient, BleakScanner

DASH_NAME    = "CQKY_XXXXXXXXX"
WRITE_CHAR   = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR  = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"

FRAME_LEN = 104
CHUNK_PAYLOAD_MAX = 100

# Number of stream updates and interval
STREAM_TICKS = 90        # 90 seconds at 1 Hz
TICK_PERIOD_S = 1.0


def log(msg: str):
    print(f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {msg}", flush=True)


def get_crc_code(buf: bytes) -> bytes:
    total = sum(buf) & 0xFF
    return bytes([((total & 0xF0) >> 4) | 0x80,
                  ( total & 0x0F)       | 0x80])


def byte_cat(buf: bytes) -> bytes:
    crc = get_crc_code(buf)
    out = bytearray(len(buf) + 2)
    out[:len(buf)] = buf
    out[len(buf) - 1:len(buf) + 1] = crc
    return bytes(out)


def build_frames(obj: dict, seq: int) -> list[bytes]:
    body = json.dumps(obj, separators=(",", ":")).encode("utf-8") + b"\x00"
    catted = byte_cat(body)
    frames = []
    i = 0
    while i < len(catted):
        chunk_len = min(len(catted) - i, CHUNK_PAYLOAD_MAX)
        frame = bytearray(FRAME_LEN)
        frame[0] = 0xFE
        struct.pack_into(">H", frame, 1, seq)
        frame[3:3 + chunk_len] = catted[i:i + chunk_len]
        frame[3 + chunk_len] = 0xFF
        frames.append(bytes(frame))
        i += chunk_len
    return frames


def make_cb(label):
    def cb(_, data):
        text = data.decode("utf-8", errors="replace")
        if '"altitude":\t17' in text and '"msg_type":\t17' in text:
            return
        if '"item":\t1' in text and '"current":\t0' in text and '"max":\t0' in text:
            return
        hexs = " ".join(f"{b:02x}" for b in data[:20])
        log(f"<-- [{label}] {len(data)}B  HEX: {hexs}")
        if any(32 <= b < 127 for b in data):
            printable = "".join(chr(b) if 32 <= b < 127 else "·"
                                for b in data[:180])
            log(f"          ASCII: {printable}")
    return cb


async def send_obj(client, obj, seq, label=""):
    frames = build_frames(obj, seq)
    for fr in frames:
        await client.write_gatt_char(WRITE_CHAR, fr, response=False)
        await asyncio.sleep(0.04)
    if label:
        log(f"-> {label} (seq={seq})")


async def main():
    log(f"scanning for {DASH_NAME}...")
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        log("dash not found"); sys.exit(1)
    log(f"found: {dev.address}")

    async with BleakClient(dev) as client:
        log(f"connected: {client.is_connected}")
        await client.start_notify(NOTIFY_CHAR, make_cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, make_cb("ffe3"))
        await asyncio.sleep(2.0)
        log("=" * 70)

        seq = 1

        # Handshake first
        await send_obj(client, {"msg_id": 13}, seq, "requestVersionCode")
        seq = (seq + 1) & 0xFFFF
        await asyncio.sleep(2.0)
        await send_obj(client,
                       {"msg_id": 24, "unique_info": "kove-hack"},
                       seq, "sendLinkInfo")
        seq = (seq + 1) & 0xFFFF
        await asyncio.sleep(1.0)

        # Send "nav destination" once to set state, then stream updates
        await send_obj(client,
                       {"msg_id": 27, "func": "NAVI", "act": 1,
                        "dest": "Pearl Street, Boulder"},
                       seq, "sendNaviDest (set destination)")
        seq = (seq + 1) & 0xFFFF
        await asyncio.sleep(1.0)

        log("=" * 70)
        log(f"STARTING CONTINUOUS NAV STREAM: {STREAM_TICKS} updates at "
            f"{TICK_PERIOD_S}s interval. WATCH THE DASH.")
        log("=" * 70)

        # Simulate approach: distance shrinks from 2000m to 100m over the stream
        start_dist = 2000
        end_dist = 100
        start_time = 240
        for i in range(STREAM_TICKS):
            t = i / max(STREAM_TICKS - 1, 1)
            cur_dist_m = int(start_dist - (start_dist - end_dist) * t)
            cur_remain_s = int(start_time * (1 - t))
            path_remain_m = 8000 - i * 30
            retain_rate = int(10 + 80 * t)  # 10% to 90%

            # Modern format: msg_id 27 NAVI act=3
            modern = {
                "msg_id": 27, "func": "NAVI", "act": 3,
                "icon": 1,  # straight arrow; 2=right turn, 3=left, etc.
                "next_road": "Pearl Street",
                "cur_retain_distance": str(cur_dist_m),
                "cur_unittype": 0,  # 0=meters
                "path_retain_distance": str(path_remain_m),
                "path_cur_unittype": 0,
                "cur_retain_time": cur_remain_s,
                "remain_time": cur_remain_s,
                "retain_rate": retain_rate,
            }
            await send_obj(client, modern, seq,
                           f"NAVI act=3 tick {i+1}/{STREAM_TICKS} dist={cur_dist_m}m time={cur_remain_s}s")
            seq = (seq + 1) & 0xFFFF
            await asyncio.sleep(0.1)

            # Also legacy msg_id 1 — same fields, integer distances
            legacy = {
                "msg_id": 1,
                "icon": 1,
                "next_road": "Pearl Street",
                "cur_retain_distance": cur_dist_m,
                "path_retain_distance": path_remain_m,
                "remain_time": cur_remain_s,
            }
            await send_obj(client, legacy, seq, f"msg_id 1 legacy tick {i+1}")
            seq = (seq + 1) & 0xFFFF

            await asyncio.sleep(max(0, TICK_PERIOD_S - 0.2))

        log("=" * 70)
        log("Stream done. Sending msg_id 15 (endNavi) to clean up.")
        await send_obj(client, {"msg_id": 15}, seq, "endNavi")
        seq = (seq + 1) & 0xFFFF
        await asyncio.sleep(5.0)
        log("done")


if __name__ == "__main__":
    asyncio.run(main())
