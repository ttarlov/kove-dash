#!/usr/bin/env python3
"""Establish a BLE connection to the dash, send the OEM initial handshake
with correct 104B framing, then hold the connection.

Run alongside dash_server.py so both transports are live — the dash may
require both before it dials port 15456.

Handshake order from BleConnectWrapper.handleMessage (lines 922-1024):
  1. msg_id 13                        requestVersionCode
  2. (wait for inbound msg_id 10 item 6 version reply)
  3. msg_id 24 unique_info=<nickname>  sendLinkInfo
  4. msg_id 26                        requestProductType
  5. msg_id 54                        checkVehicleCurStatus
  6. msg_id 27 INSIDENAVI query=2     queryDevicePlayerVoiceStatus
  7. msg_id 27 INSIDENAVI query=1     queryInsideNaviStatus
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


async def send_message(client, obj: dict, seq: int, label: str):
    frames = build_frames(obj, seq)
    log(f"--> {label}  (seq={seq}, {len(frames)} frame(s))")
    log(f"    json: {json.dumps(obj, separators=(',', ':'))}")
    for fr in frames:
        await client.write_gatt_char(WRITE_CHAR, fr, response=False)
        await asyncio.sleep(0.05)


def make_cb(label):
    def cb(_, data):
        text = data.decode("utf-8", errors="replace")
        if '"altitude":\t17' in text and '"msg_type":\t17' in text:
            return  # skip altitude spam
        if '"item":\t1' in text and '"current":\t0' in text:
            return  # skip idle speed spam
        hexs = " ".join(f"{b:02x}" for b in data[:24])
        log(f"<-- [{label}] {len(data)}B  HEX: {hexs}")
        if any(32 <= b < 127 for b in data):
            printable = "".join(chr(b) if 32 <= b < 127 else "·"
                                for b in data[:120])
            log(f"          ASCII: {printable}")
    return cb


async def main():
    hold_seconds = 300
    log(f"scanning for {DASH_NAME}...")
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        log("dash not found via BLE scan")
        sys.exit(1)
    log(f"found: {dev.address}")

    async with BleakClient(dev) as client:
        log(f"connected: {client.is_connected}")
        await client.start_notify(NOTIFY_CHAR, make_cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, make_cb("ffe3"))
        await asyncio.sleep(1.5)
        log("notifies subscribed; firing OEM BLE handshake...")

        seq = 1
        await send_message(client, {"msg_id": 13}, seq, "requestVersionCode")
        seq += 1
        await asyncio.sleep(2.0)

        await send_message(client, {"msg_id": 24, "unique_info": "kove-hack"},
                            seq, "sendLinkInfo")
        seq += 1
        await asyncio.sleep(0.5)

        await send_message(client, {"msg_id": 26}, seq, "requestProductType")
        seq += 1
        await asyncio.sleep(0.5)

        await send_message(client, {"msg_id": 54}, seq, "checkVehicleCurStatus")
        seq += 1
        await asyncio.sleep(0.5)

        await send_message(client,
                           {"msg_id": 27, "func": "INSIDENAVI", "query": 2},
                           seq, "queryDevicePlayerVoiceStatus")
        seq += 1
        await asyncio.sleep(0.5)

        await send_message(client,
                           {"msg_id": 27, "func": "INSIDENAVI", "query": 1},
                           seq, "queryInsideNaviStatus")
        seq += 1
        await asyncio.sleep(0.5)

        # Also try TUC forge via BLE for completeness
        await send_message(client,
                           {"msg_id": 27, "func": "TUC", "act": "SAVE",
                            "tuc": "KOVE450RHACK0123"},
                           seq, "TUC SAVE (forge)")
        seq += 1
        await asyncio.sleep(0.3)
        await send_message(client,
                           {"msg_id": 27, "func": "TUC", "act": "STATUS",
                            "tucs": 1},
                           seq, "TUC STATUS tucs=1 (forge)")
        seq += 1

        log("=" * 70)
        log(f"handshake done. Holding BLE for {hold_seconds}s. "
            "Watch dash_server.log for 15456 dial.")
        log("=" * 70)
        await asyncio.sleep(hold_seconds)
        log("disconnecting...")


if __name__ == "__main__":
    asyncio.run(main())
