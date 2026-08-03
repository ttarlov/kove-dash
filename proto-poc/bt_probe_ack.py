#!/usr/bin/env python3
"""Probe the ffe3 ACK semantics.
1. Read ffe3 fresh
2. Send a deliberately malformed write — does the ACK differ?
3. Send valid OEM-like JSON — what's the ACK?
4. Send EMPTY write — what happens?
5. Read ffe3 again — has it changed?"""
import asyncio
import json
import sys
from bleak import BleakClient, BleakScanner

DASH_NAME = "CQKY_XXXXXXXXX"
WRITE_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found", file=sys.stderr); sys.exit(1)

    def cb(label):
        def f(_, data):
            text = data.decode("utf-8", errors="replace")
            if '"altitude":\t17' in text and '"msg_type":\t17' in text:
                return
            hexs = " ".join(f"{b:02x}" for b in data[:24])
            print(f"  << [{label}] {len(data)}B HEX: {hexs}", flush=True)
        return f

    async with BleakClient(dev) as client:
        await client.start_notify(NOTIFY_CHAR, cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, cb("ffe3"))
        await asyncio.sleep(1.0)

        v = await client.read_gatt_char(CONTROL_CHAR)
        print(f"\n[A] ffe3 initial read: {len(v)}B HEX: {' '.join(f'{b:02x}' for b in v)}", flush=True)
        await asyncio.sleep(1.0)

        print("\n[B] write empty bytes to ffe1")
        try:
            await client.write_gatt_char(WRITE_CHAR, b"", response=False)
        except Exception as e:
            print(f"  error: {e}")
        await asyncio.sleep(2.0)

        print("\n[C] write GARBAGE to ffe1")
        await client.write_gatt_char(WRITE_CHAR, b"\xde\xad\xbe\xef\xca\xfe", response=False)
        await asyncio.sleep(2.0)

        print("\n[D] write VALID-LOOKING JSON to ffe1")
        await client.write_gatt_char(WRITE_CHAR, json.dumps({"msg_id": 27, "func": "CAR_INFO", "act": "get_car_info"}, separators=(",", ":")).encode(), response=False)
        await asyncio.sleep(2.0)

        print("\n[E] write SHORT valid JSON to ffe1")
        await client.write_gatt_char(WRITE_CHAR, b'{"msg_id":10}', response=False)
        await asyncio.sleep(2.0)

        v2 = await client.read_gatt_char(CONTROL_CHAR)
        print(f"\n[F] ffe3 read after writes: {len(v2)}B HEX: {' '.join(f'{b:02x}' for b in v2)}", flush=True)

        await asyncio.sleep(2.0)
        print("\ndone")


if __name__ == "__main__":
    asyncio.run(main())
