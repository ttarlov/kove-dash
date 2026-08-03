#!/usr/bin/env python3
"""v2: Send activation-gated msg_id 1-6 via BLE with CORRECT 104B framing.

The v1 of this script sent raw JSON to ffe1 — the dash silently dropped every
write because it expects the OEM 104-byte chunked frame. This v2 reproduces the
exact framing from WriteThread.addPackageToList (WriteThread.java:141-180) and
ByteUtils.byteCat + getCRCCode (ByteUtils.java:25-31, :127-133).

Wire format per BLE write (always exactly 104 bytes):
    byte[0]   = 0xFE                       (frame start)
    byte[1-2] = seq number (u16, BE)       (incremented per packet)
    byte[3..3+chunk_len-1] = byteCat(json + '\0') chunk, up to 100 bytes
    byte[3+chunk_len] = 0xFF              (frame end)
    byte[3+chunk_len+1 .. 103] = 0x00     (zero padding)

byteCat overlays a 2-byte "CRC" on the last byte of (json + '\0'):
    out = (json+'\0')[:-1] + crc_high + crc_low + 0x00
where crc bytes are nibble-split byte-sum with 0x80 set in each nibble.

msg_id 1-6 are the dash's screen-content commands (sendNaviInfoOld,
sendNotification, sendIncallInfo, sendCross, sendMMS). The OEM blocks these
client-side unless isActivate(). We don't have that filter.

If the dash itself trusts the BLE write and doesn't have an independent
activation gate on inbound msg_id 1-6, the dash should render our nav info
NATIVELY — no projection needed.
"""
import asyncio
import json
import struct
import sys
from bleak import BleakClient, BleakScanner

DASH_NAME = "CQKY_XXXXXXXXX"
WRITE_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"

CHUNK_PAYLOAD_MAX = 100   # bytes 3..103 minus 1 (for trailing 0xFF marker)
FRAME_LEN = 104


def get_crc_code(buf: bytes) -> bytes:
    """ByteUtils.getCRCCode — nibble-split byte-sum with 0x80 set."""
    total = 0
    for b in buf:
        total = (total + b) & 0xFF
    return bytes([((total & 0xF0) >> 4) | 0x80,
                  ( total & 0x0F)       | 0x80])


def byte_cat(buf: bytes) -> bytes:
    """ByteUtils.byteCat — overlays the 2-byte CRC on the last byte of buf.

    Result length = len(buf) + 2:
      out[:-3] = buf[:-1]      (everything except last input byte)
      out[-3]  = crc_high      (replaces last input byte)
      out[-2]  = crc_low       (appended)
      out[-1]  = 0x00          (default-zero, never written)
    """
    crc = get_crc_code(buf)
    out = bytearray(len(buf) + 2)
    out[:len(buf)] = buf
    out[len(buf) - 1:len(buf) + 1] = crc  # overlap last byte with crc_high, append crc_low
    return bytes(out)


def build_ble_frames(json_obj: dict, seq: int) -> list[bytes]:
    """Returns one or more 104-byte BLE frames for the given JSON object."""
    body = json.dumps(json_obj, separators=(",", ":")).encode("utf-8") + b"\x00"
    catted = byte_cat(body)

    frames = []
    i = 0
    while i < len(catted):
        chunk_len = min(len(catted) - i, CHUNK_PAYLOAD_MAX)
        frame = bytearray(FRAME_LEN)
        frame[0] = 0xFE
        struct.pack_into(">H", frame, 1, seq)       # seq big-endian u16
        frame[3:3 + chunk_len] = catted[i:i + chunk_len]
        frame[3 + chunk_len] = 0xFF
        # remaining bytes stay 0x00
        frames.append(bytes(frame))
        i += chunk_len
    return frames


# In order: most visible to least.
TESTS = [
    ("msg_id=1 sendNaviInfoOld - SHOULD DISPLAY NAVIGATION on dash",
     {
         "msg_id": 1,
         "icon": 1,                       # turn-arrow icon ID
         "next_road": "Pearl Street",
         "cur_retain_distance": 500,      # meters to next turn
         "path_retain_distance": 5000,    # total route distance remaining
         "remain_time": 600,              # seconds
     }),
    ("msg_id=3 sendIncallInfo - SHOULD DISPLAY 'Incoming Call from Mom' on dash",
     {"msg_id": 3, "name": "Mom", "number": "+15551234567"}),
    ("msg_id=2 sendNotification - SHOULD DISPLAY NOTIFICATION on dash",
     {
         "msg_id": 2,
         "app_name": "Test",
         "title": "Hello from the hack",
         "content": "If you can read this we won",
         "package_name": "com.test",
     }),
    ("msg_id=6 sendMMS - SHOULD DISPLAY SMS-like message on dash",
     {"msg_id": 6, "title": "Test SMS", "content": "test body"}),
]


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found", file=sys.stderr); sys.exit(1)

    def cb(label):
        def f(_, data):
            text = data.decode("utf-8", errors="replace")
            if '"altitude":\t17' in text and '"msg_type":\t17' in text:
                return  # skip altitude spam
            hexs = " ".join(f"{b:02x}" for b in data[:24])
            print(f"  << [{label}] {len(data)}B  HEX: {hexs}", flush=True)
            if any(32 <= b < 127 for b in data):
                printable = "".join(chr(b) if 32 <= b < 127 else "·"
                                    for b in data[:80])
                print(f"           ASCII: {printable}", flush=True)
        return f

    async with BleakClient(dev) as client:
        await client.start_notify(NOTIFY_CHAR, cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, cb("ffe3"))
        await asyncio.sleep(2.0)
        print(f"connected — WATCH THE DASH SCREEN", flush=True)
        print("=" * 70, flush=True)

        # Seq counter increments per addPackageToList call (per logical msg).
        seq = 1
        for label, payload in TESTS:
            print(f"\n>> {label}", flush=True)
            print(f"   payload: {json.dumps(payload, separators=(',', ':'))}",
                  flush=True)
            frames = build_ble_frames(payload, seq)
            print(f"   framing: seq={seq}, {len(frames)} BLE write(s) of 104B "
                  f"each (total body = {sum(len(f) for f in frames)}B)",
                  flush=True)
            try:
                for fi, frame in enumerate(frames):
                    head = " ".join(f"{b:02x}" for b in frame[:16])
                    print(f"     frame {fi}: 0xFE {seq:04x} … {head[6:]}",
                          flush=True)
                    await client.write_gatt_char(WRITE_CHAR, frame,
                                                  response=False)
                    await asyncio.sleep(0.05)  # small gap between chunks
                print(f"   written — watch dash for ~7s", flush=True)
            except Exception as e:
                print(f"   ERROR: {e}", flush=True)
            seq = (seq + 1) & 0xFFFF
            await asyncio.sleep(7.0)

        print("\n" + "=" * 70)
        print("holding 10s for late responses...", flush=True)
        await asyncio.sleep(10)
        print("done", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
