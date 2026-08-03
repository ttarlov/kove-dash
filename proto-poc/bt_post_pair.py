#!/usr/bin/env python3
"""After GATT connect, replay the OEM post-pair BLE message sequence:
queryVersion → queryActivateStatus → requestMirrorStatus. Watch notifications.
Run alongside dash_server.py to see if any of these causes the dash to dial 15456.

JSON wire format (from observed dash output): tab-formatted JSON,
LF newlines: `{\n\t"msg_id":\t13\n}`.
The OEM might or might not use this exact whitespace — try both."""
import asyncio
import json
import sys
from bleak import BleakClient, BleakScanner


DASH_NAME = "CQKY_XXXXXXXXX"
WRITE_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"


def format_json_compact(obj: dict) -> bytes:
    """OEM parser strips whitespace; compact JSON minimizes packet size."""
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


async def write_jsonish(client, char_uuid, obj, label):
    payload = format_json_compact(obj)
    print(f">> WRITE {label}  ({len(payload)}B): {payload.decode('utf-8')}", flush=True)
    await client.write_gatt_char(char_uuid, payload, response=False)


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found", file=sys.stderr); sys.exit(1)

    notif_count = {"n": 0}

    def make_cb(label):
        def cb(_, data):
            notif_count["n"] += 1
            text_preview = data.decode("utf-8", errors="replace")
            print(f"<< NOTIF {label}: {text_preview}", flush=True)
        return cb

    async with BleakClient(dev) as client:
        print(f"connected: {client.is_connected}", flush=True)
        await client.start_notify(NOTIFY_CHAR, make_cb("ffe2"))
        await client.start_notify(CONTROL_CHAR, make_cb("ffe3"))
        await asyncio.sleep(1.0)
        print(f"\n=== POST-PAIR SEQUENCE ===", flush=True)
        # 1. Replicate OEM queryBaseInfo() — version + activation + location
        await write_jsonish(client, CONTROL_CHAR, {"msg_id": 13}, "queryVersion")
        await asyncio.sleep(1.5)
        await write_jsonish(client, CONTROL_CHAR, {"msg_id": 27, "func": "TUC", "act": "GET"},
                            "queryActivateStatus")
        await asyncio.sleep(1.5)
        # 2. Mirror status — the projection trigger candidate
        await write_jsonish(client, CONTROL_CHAR, {"msg_id": 25, "msg_type": 24, "msg_source": 2, "status": 1},
                            "requestMirrorStatus")
        await asyncio.sleep(1.5)
        # 3. Pair info ack — maybe the dash needs us to send back pair info
        await write_jsonish(client, CONTROL_CHAR, {"msg_id": 27, "func": "PAIR", "act": "send_pairinfo", "info": 1},
                            "sendPairInfo")
        await asyncio.sleep(1.5)
        # 4. Phone link info — phone identifies itself
        await write_jsonish(client, CONTROL_CHAR, {"msg_id": 27, "func": "LINK", "act": "send", "nickname": "kove-hack"},
                            "sendLinkInfo")
        await asyncio.sleep(1.5)

        print(f"\n=== HOLDING 30s, watching for activity ===", flush=True)
        await asyncio.sleep(30)
        print(f"\nTotal notifications received: {notif_count['n']}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
