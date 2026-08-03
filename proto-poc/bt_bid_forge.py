#!/usr/bin/env python3
"""Send msg_id 50 sendActivateVehicle with a forged BID via BLE.

Theory (from JsonManager.sendActivateVehicle line 844-855 +
BleConnectWrapper.handleMessage cascade):
  - Dash sends LockStatus event (msg_id 10 item 53) with need_active=1, bid=...
  - Phone POSTs to cloud /relation, gets back account-binding success
  - Phone sends msg_id 50 with BID over BLE
  - Dash flips internal "cloud-bound" flag, starts dialing TCP 15456/15457

We've never sent msg_id 50. This is the highest-value untested message.
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
                                for b in data[:160])
            log(f"          ASCII: {printable}")
    return cb


# Several BID-shape variants since we don't know if dash validates format
BID_VARIANTS = [
    "FAKE1234567890ABCDEF",                       # 20 hex-ish chars
    "00000000-0000-0000-0000-000000000000",       # UUID-shape
    "kove-450-bid-test-0001",                     # ASCII descriptive
    "1",                                          # minimal "non-empty"
]


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

        async def send(label, obj):
            nonlocal seq
            log("")
            log(f">> {label}")
            log(f"   json: {json.dumps(obj, separators=(',', ':'))}")
            frames = build_frames(obj, seq)
            for fr in frames:
                await client.write_gatt_char(WRITE_CHAR, fr, response=False)
                await asyncio.sleep(0.05)
            log(f"   sent (seq={seq})")
            seq = (seq + 1) & 0xFFFF

        # Minimal handshake first
        await send("requestVersionCode", {"msg_id": 13})
        await asyncio.sleep(2.0)
        await send("sendLinkInfo", {"msg_id": 24, "unique_info": "kove-hack"})
        await asyncio.sleep(1.0)

        # Forge TUC SAVE+STATUS first (as before)
        await send("TUC SAVE forge",
                   {"msg_id": 27, "func": "TUC", "act": "SAVE",
                    "tuc": "KOVE450RHACK0123"})
        await asyncio.sleep(0.5)
        await send("TUC STATUS forge",
                   {"msg_id": 27, "func": "TUC", "act": "STATUS", "tucs": 1})
        await asyncio.sleep(1.0)

        # NOW the new piece: msg_id 50 with different BID shapes
        for bid in BID_VARIANTS:
            await send(f"sendActivateVehicle BID='{bid}'",
                       {"msg_id": 50, "BID": bid})
            await asyncio.sleep(10.0)  # 10s between each — watch dash!

        log("")
        log("=" * 70)
        log("All BID variants sent. Holding 60s for late activity...")
        await asyncio.sleep(60)
        log("done")


if __name__ == "__main__":
    asyncio.run(main())
