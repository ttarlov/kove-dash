#!/usr/bin/env python3
"""Shotgun BLE probe — try many nav/screen variants in one session.

Connects BLE, fires the OEM-style nav startup with the NEWER msg_id 27 NAVI
schema (act=1 dest, act=2 retain, act=3 nav update). Also sends the legacy
msg_id 1 in case the dash listens to either, plus INSIDENAVI inside_naviinfo
(which sets up a route), plus ROAD_NAVI get_status (a query).

Per JsonManager.java findings:
- msg_id 27 NAVI act=1: sendNaviDest {dest: "..."}
- msg_id 27 NAVI act=2: sendNaviRetain {curtime, totaltime}
- msg_id 27 NAVI act=3: full nav update with icon, next_road, string distances,
  unit types, time fields, retain_rate
- msg_id 27 INSIDENAVI CMD=inside_naviinfo: set up nav route (start/end coords)
- msg_id 27 ROAD_NAVI get_status: ask dash for nav state
- msg_id 1: legacy sendNaviInfoOld (we already tried)

After each write we hold ~5s and watch for any new screen activity (user
reports).
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


# Sequence: minimal handshake, then nav-mode setup, then nav updates.
TESTS = [
    ("requestVersionCode (handshake)",
     {"msg_id": 13}),

    ("sendLinkInfo (identify)",
     {"msg_id": 24, "unique_info": "kove-hack"}),

    ("checkVehicleCurStatus",
     {"msg_id": 54}),

    ("ROAD_NAVI get_status (probe nav state)",
     {"msg_id": 27, "func": "ROAD_NAVI", "act": "get_status"}),

    ("INSIDENAVI inside_naviinfo (set up route Boulder -> Denver)",
     {"msg_id": 27, "func": "INSIDENAVI", "CMD": "inside_naviinfo",
      "start": "40.0150,-105.2705",
      "waynum": 0,
      "end": "39.7392,-104.9903",
      "routeId": 0,
      "strategy": 0,
      "dst": "Denver",
      "naviType": 0,
      "carType": 0,
      "isRestricted": 0,
      "carNumber": "",
      "carCC": 450,
      "themeMode": 0}),

    ("NAVI act=1 sendNaviDest (set destination name)",
     {"msg_id": 27, "func": "NAVI", "act": 1, "dest": "Pearl Street, Boulder"}),

    ("NAVI act=3 getNaviInfo (modern nav update)",
     {"msg_id": 27, "func": "NAVI", "act": 3,
      "icon": 1,
      "next_road": "Pearl Street",
      "cur_retain_distance": "500",
      "cur_unittype": 0,
      "path_retain_distance": "5000",
      "path_cur_unittype": 0,
      "cur_retain_time": 60,
      "remain_time": 600,
      "retain_rate": 10}),

    ("NAVI act=2 sendNaviRetain (time stats)",
     {"msg_id": 27, "func": "NAVI", "act": 2, "curtime": 60, "totaltime": 600}),

    ("legacy msg_id 1 sendNaviInfoOld (in case modern doesn't render)",
     {"msg_id": 1,
      "icon": 1,
      "next_road": "Pearl Street",
      "cur_retain_distance": 500,
      "path_retain_distance": 5000,
      "remain_time": 600}),

    ("Repeat NAVI act=3 (continuous updates - some firmware needs stream)",
     {"msg_id": 27, "func": "NAVI", "act": 3,
      "icon": 1, "next_road": "Pearl Street",
      "cur_retain_distance": "400", "cur_unittype": 0,
      "path_retain_distance": "4900", "path_cur_unittype": 0,
      "cur_retain_time": 50, "remain_time": 590, "retain_rate": 12}),
]


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
                                for b in data[:140])
            log(f"          ASCII: {printable}")
    return cb


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
        for label, payload in TESTS:
            log(f"")
            log(f">> {label}")
            log(f"   json: {json.dumps(payload, separators=(',', ':'))}")
            frames = build_frames(payload, seq)
            log(f"   framing: seq={seq}, {len(frames)} frame(s)")
            try:
                for fr in frames:
                    await client.write_gatt_char(WRITE_CHAR, fr, response=False)
                    await asyncio.sleep(0.05)
                log("   ** SENT — watch dash for 6s **")
            except Exception as e:
                log(f"   ERROR: {e}")
            seq = (seq + 1) & 0xFFFF
            await asyncio.sleep(6.0)

        log("")
        log("=" * 70)
        log("All variants sent. Holding 30s for late activity...")
        await asyncio.sleep(30)
        log("done")


if __name__ == "__main__":
    asyncio.run(main())
