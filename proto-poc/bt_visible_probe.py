#!/usr/bin/env python3
"""Bike-on probe focused on commands MOST LIKELY to produce a visible change
on the dash. Builds on the bench-confirmed BLE framing.

Ordered from "cheapest unambiguous visual" (button press, unit toggle) to
"complex but high-impact" (open ROAD_NAVI screen + push map bitmap).

Key insight from JsonManager catalog: ROAD_NAVI map commands require
request_start FIRST. Previous tests sent them cold — that's why they failed.

Run while bike is on, BLE not occupied by another client. Holds 6-15s after
each write so the user can report.
"""
import asyncio
import base64
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


# Tiny 4x4 red PNG (76 bytes Base64) for sendCross/first_map smoke tests
TINY_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAEAQMAAACTPww9AAAAA1BMVEX/AAAZ4gk3AAAACklEQVR4n"
    "GNgAAAAAgABc3UBGAAAAABJRU5ErkJggg=="
)


# Each test = (label, JSON, hold_seconds, notes_for_user)
TESTS = [
    # Stage A — Cheap, unambiguous "did the dash react to ANYTHING?"
    ("KEY press UP (highest-confidence visual)",
     {"msg_id": 27, "func": "KEY", "act": "UP"}, 4,
     "Watch for menu cursor / scroll movement"),
    ("KEY press DOWN",
     {"msg_id": 27, "func": "KEY", "act": "DOWN"}, 4, "Same"),
    ("KEY press OK",
     {"msg_id": 27, "func": "KEY", "act": "OK"}, 4, "Watch for screen change"),
    ("KEY press MENU",
     {"msg_id": 27, "func": "KEY", "act": "MENU"}, 4, "Watch for menu open"),
    ("KEY press HOME",
     {"msg_id": 27, "func": "KEY", "act": "HOME"}, 4, ""),

    # Stage B — Cheap visible toggles (unit, language)
    ("Toggle UNIT to imperial (msg_id 25 msg_type 14, unit=1)",
     {"msg_id": 25, "msg_type": 14, "msg_source": 2, "unit": 1}, 6,
     "Speed/distance digits should switch to mi"),
    ("Toggle UNIT to metric (unit=0)",
     {"msg_id": 25, "msg_type": 14, "msg_source": 2, "unit": 0}, 6,
     "Speed/distance digits should switch to km"),
    ("Toggle LANGUAGE to Chinese (msg_id 25 msg_type 13, language=0)",
     {"msg_id": 25, "msg_type": 13, "msg_source": 2, "language": 0}, 6,
     "Menu labels should switch"),
    ("Toggle LANGUAGE to English (language=1)",
     {"msg_id": 25, "msg_type": 13, "msg_source": 2, "language": 1}, 6, "Same"),

    # Stage C — GPS / signal indicators
    ("GPS signal 0 bars",
     {"msg_id": 27, "func": "GPS", "act": "signal_status", "status": 0}, 4,
     "GPS icon should show no-signal"),
    ("GPS signal 4 bars",
     {"msg_id": 27, "func": "GPS", "act": "signal_status", "status": 4}, 4,
     "GPS icon should show full signal"),

    # Stage D — Mirror status (the explicit screen-mirror toggle)
    ("setMirrorStatus status=1 (msg_id 25 msg_type 23)",
     {"msg_id": 25, "msg_type": 23, "msg_source": 2, "status": 1}, 8,
     "Dash may enter mirror mode / clear screen for projection"),
    ("setMirrorStatus status=0",
     {"msg_id": 25, "msg_type": 23, "msg_source": 2, "status": 0}, 4, ""),

    # Stage E — Theme switch
    ("THEME act=1",
     {"msg_id": 27, "func": "THEME", "act": 1}, 4,
     "Dash colors may change"),
    ("THEME act=0",
     {"msg_id": 27, "func": "THEME", "act": 0}, 4, ""),

    # Stage F — Open ROAD_NAVI screen then push map bitmap
    ("ROAD_NAVI request_start result=1 (open nav screen)",
     {"msg_id": 27, "func": "ROAD_NAVI", "act": "request_start",
      "result": 1}, 6,
     "Dash should switch to nav screen"),
    ("ROAD_NAVI first_map (tiny red bitmap)",
     {"msg_id": 27, "func": "ROAD_NAVI", "act": "first_map",
      "cx": 2, "cy": 2, "angle": 0, "day_or_night": 0,
      "map_data": TINY_PNG_B64}, 8,
     "Red square should appear on dash"),
    ("ROAD_NAVI request_close",
     {"msg_id": 27, "func": "ROAD_NAVI", "act": "request_close",
      "dir": 1, "type": 0, "result": 0}, 4,
     "Dash should return to previous screen"),

    # Stage G — Turn-arrow bitmap (msg_id 4 sendCross)
    ("msg_id 4 sendCross (tiny red bitmap)",
     {"msg_id": 4, "icon": TINY_PNG_B64}, 6,
     "Turn arrow icon should appear"),

    # Stage H — Debug / test mode
    ("hanjd_test connect (debug mode?)",
     {"msg_id": 27, "func": "hanjd_test", "act": "connect"}, 6,
     "Dash may enter test/diag mode"),
    ("hanjd_test stop",
     {"msg_id": 27, "func": "hanjd_test", "act": "stop"}, 4, ""),

    # Stage I — Music UI (renders music widget)
    ("MUSIC ret_status playing",
     {"msg_id": 27, "func": "MUSIC", "act": "ret_status", "status": 1}, 4,
     "Music icon/widget may show"),
    ("MUSIC ret_msg (with metadata)",
     {"msg_id": 27, "func": "MUSIC", "act": "ret_msg",
      "title": "Test Song", "author": "Test Artist", "album": "Test Album",
      "lyrics": ""}, 4, "Music title may appear"),
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
                                for b in data[:180])
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
        log("Visible-effect probe begins. Watch dash screen for each test.")
        log("=" * 70)

        # Minimal handshake first
        seq = 1
        for boot_label, boot_obj in [
            ("requestVersionCode", {"msg_id": 13}),
            ("sendLinkInfo", {"msg_id": 24, "unique_info": "kove-hack"}),
        ]:
            log(f">> bootstrap {boot_label}")
            frames = build_frames(boot_obj, seq)
            for fr in frames:
                await client.write_gatt_char(WRITE_CHAR, fr, response=False)
                await asyncio.sleep(0.05)
            seq = (seq + 1) & 0xFFFF
            await asyncio.sleep(1.0)

        log("=" * 70)

        for label, payload, hold, note in TESTS:
            log("")
            log(f">> {label}")
            if note:
                log(f"   note: {note}")
            log(f"   json: {json.dumps(payload, separators=(',', ':'))}")
            frames = build_frames(payload, seq)
            try:
                for fr in frames:
                    await client.write_gatt_char(WRITE_CHAR, fr, response=False)
                    await asyncio.sleep(0.05)
                log(f"   ** SENT (seq={seq}, {len(frames)} frame(s)). "
                    f"Hold {hold}s — watch the dash. **")
            except Exception as e:
                log(f"   ERROR: {e}")
            seq = (seq + 1) & 0xFFFF
            await asyncio.sleep(hold)

        log("")
        log("=" * 70)
        log("All tests sent. Holding 30s.")
        await asyncio.sleep(30)
        log("done")


if __name__ == "__main__":
    asyncio.run(main())
