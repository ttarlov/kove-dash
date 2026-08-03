#!/usr/bin/env python3
"""Send the activation-gated msg_id 1-6 messages directly via BLE.
The OEM client-side blocks these unless isActivate()==true. We don't.
If the dash itself accepts them, navigation/notification/call display
should appear on the dash screen WITHOUT needing projection."""
import asyncio
import json
import sys
from bleak import BleakClient, BleakScanner

DASH_NAME = "CQKY_XXXXXXXXX"
WRITE_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"


def j(obj):
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


# In order: most visible to least
TESTS = [
    ("msg_id=1 sendNaviInfoOld - SHOULD DISPLAY NAVIGATION on dash",
     j({
         "msg_id": 1,
         "icon": 1,                       # turn-arrow icon ID
         "next_road": "Pearl Street",
         "cur_retain_distance": 500,      # meters to next turn
         "path_retain_distance": 5000,    # total route distance remaining
         "remain_time": 600,              # seconds
     })),
    ("msg_id=3 sendIncallInfo - SHOULD DISPLAY 'Incoming Call from Mom' on dash",
     j({"msg_id": 3, "name": "Mom", "number": "+15551234567"})),
    ("msg_id=2 sendNotification - SHOULD DISPLAY NOTIFICATION on dash",
     j({
         "msg_id": 2,
         "app_name": "Test",
         "title": "Hello from the hack",
         "content": "If you can read this we won",
         "package_name": "com.test",
     })),
    ("msg_id=6 sendMMS - SHOULD DISPLAY SMS-like message on dash",
     j({"msg_id": 6, "title": "Test SMS", "content": "test body"})),
]


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found", file=sys.stderr); sys.exit(1)

    def cb(label):
        def f(_, data):
            text = data.decode("utf-8", errors="replace")
            # Skip the altitude spam
            if '"altitude":\t17' in text and '"msg_type":\t17' in text:
                return
            hexs = " ".join(f"{b:02x}" for b in data[:24])
            print(f"  << [{label}] {len(data)}B  HEX: {hexs}", flush=True)
            if any(32 <= b < 127 for b in data):
                printable = "".join(chr(b) if 32 <= b < 127 else "·" for b in data[:80])
                print(f"           ASCII: {printable}", flush=True)
        return f

    async with BleakClient(dev) as client:
        await client.start_notify(NOTIFY_CHAR, cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, cb("ffe3"))
        await asyncio.sleep(2.0)
        print(f"connected — WATCH THE DASH SCREEN", flush=True)
        print("=" * 70, flush=True)

        for label, payload in TESTS:
            print(f"\n>> {label}", flush=True)
            print(f"   payload: {payload.decode()}", flush=True)
            try:
                await client.write_gatt_char(WRITE_CHAR, payload, response=False)
                print(f"   written  — watch dash for ~7s", flush=True)
            except Exception as e:
                print(f"   ERROR: {e}", flush=True)
            await asyncio.sleep(7.0)

        print("\n" + "=" * 70)
        print("holding 10s for late responses...", flush=True)
        await asyncio.sleep(10)
        print("done", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
