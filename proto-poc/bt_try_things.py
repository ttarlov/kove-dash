#!/usr/bin/env python3
"""Send a curated set of BLE commands to the dash, one at a time with pauses.
Designed to surface ANY visible/audible/state change on the dash UI."""
import asyncio
import json
import sys
from bleak import BleakClient, BleakScanner


DASH_NAME = "CQKY_XXXXXXXXX"
WRITE_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"


def j(obj: dict) -> bytes:
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


TESTS = [
    ("requestCarInfo (should reply with vehicle info)",
     j({"msg_id": 27, "func": "CAR_INFO", "act": "get_car_info"})),
    ("requestMileage (force a fresh mileage broadcast)",
     j({"msg_id": 10, "item": 2})),
    ("sendTimeFunction START (should start ride timer on dash)",
     j({"msg_id": 25, "msg_type": 1, "msg_source": 2, "control_info": 1, "time": 0})),
    ("sendMusicStatus playing (should show music icon)",
     j({"msg_id": 27, "func": "MUSIC", "act": "ret_status", "status": 1})),
    ("sendThemeTask switch (try toggling theme)",
     j({"msg_id": 27, "func": "THEME", "act": 2, "task": "dark"})),
    ("requestBleMac (should reply with BLE MAC)",
     j({"msg_id": 27, "func": "BT_KEY", "act": "get_mac"})),
]


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found", file=sys.stderr); sys.exit(1)

    def make_cb(label):
        def cb(_, data):
            text = data.decode("utf-8", errors="replace")
            if '"altitude":\t17' in text and '"msg_type":\t17' in text:
                return
            # Show BOTH hex bytes AND text for everything else
            hex_bytes = " ".join(f"{b:02x}" for b in data[:32])
            tail = " …" if len(data) > 32 else ""
            print(f"  << [{label}] ({len(data)}B) HEX: {hex_bytes}{tail}", flush=True)
            if data and any(32 <= b < 127 for b in data):
                printable = "".join(chr(b) if 32 <= b < 127 else "·" for b in data[:80])
                print(f"             ASCII: {printable}", flush=True)
        return cb

    async with BleakClient(dev) as client:
        print(f"connected: {client.is_connected}\n", flush=True)
        await client.start_notify(NOTIFY_CHAR, make_cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, make_cb("ffe3"))
        await asyncio.sleep(2.0)
        print("=" * 60, flush=True)

        for label, payload in TESTS:
            print(f"\n>> {label}\n   bytes: {payload.decode()}", flush=True)
            try:
                await client.write_gatt_char(WRITE_CHAR, payload, response=False)
                print(f"   OK — watch the dash for ~5 seconds", flush=True)
            except Exception as e:
                print(f"   WRITE ERROR: {e}", flush=True)
            await asyncio.sleep(5.0)

        print("\n" + "=" * 60)
        print("All commands sent. Holding 10s for any late responses ...", flush=True)
        await asyncio.sleep(10)
        print("done", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
