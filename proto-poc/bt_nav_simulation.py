#!/usr/bin/env python3
"""Pretend to be the OEM phone in active navigation mode.
Send sendLocation + sendElevationAndPond + getNaviInfo over BLE on a loop.
Goal: trigger dash projection by asserting "I have GPS + I'm navigating."

Run dash_server.py concurrently to catch a 15456 dial-in."""
import asyncio
import json
import sys
import time
from bleak import BleakClient, BleakScanner


DASH_NAME = "CQKY_XXXXXXXXX"
WRITE_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"


def jc(obj: dict) -> bytes:
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


def msg_send_location(street: str) -> bytes:
    return jc({"msg_id": 7, "street": street})


def msg_send_elevation_and_pond(altitude: int, ave: int = None, mx: int = None,
                                pond_distance: float = 0.0, pond_time: int = 0) -> bytes:
    return jc({
        "msg_id": 25, "msg_type": 9, "msg_source": 2,
        "altitude": altitude,
        "ave_altitude": ave if ave is not None else altitude,
        "max_altitude": mx if mx is not None else altitude,
        "pond_distance": pond_distance, "pond_time": pond_time,
    })


def msg_get_navi_info(icon: int, next_road: str, cur_retain_distance: str,
                     cur_unittype: int, path_retain_distance: str, path_cur_unittype: int,
                     cur_retain_time: int, remain_time: int, retain_rate: int) -> bytes:
    return jc({
        "msg_id": 27, "func": "NAVI", "act": 3,
        "icon": icon, "next_road": next_road,
        "cur_retain_distance": cur_retain_distance, "cur_unittype": cur_unittype,
        "path_retain_distance": path_retain_distance, "path_cur_unittype": path_cur_unittype,
        "cur_retain_time": cur_retain_time, "remain_time": remain_time,
        "retain_rate": retain_rate,
    })


def msg_request_mirror_on() -> bytes:
    return jc({"msg_id": 25, "msg_type": 24, "msg_source": 2, "status": 1})


async def write_msg(client, char, label, payload):
    print(f">> {label:25s} ({len(payload):3d}B) {payload.decode()}", flush=True)
    try:
        await client.write_gatt_char(char, payload, response=False)
    except Exception as e:
        print(f"   WRITE ERROR: {e}", flush=True)


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found", file=sys.stderr); sys.exit(1)

    notif_count = {"n": 0}
    saw_new_msg = {"saw": False}

    def cb(_, data):
        notif_count["n"] += 1
        text = data.decode("utf-8", errors="replace")
        # Filter out the repetitive altitude messages so we see new stuff
        if '"altitude":\t17' in text and '"msg_type":\t17' in text:
            return  # skip the spam
        saw_new_msg["saw"] = True
        print(f"<< NEW! {text}", flush=True)

    async with BleakClient(dev) as client:
        print(f"connected: {client.is_connected}", flush=True)
        await client.start_notify(NOTIFY_CHAR, cb)
        await client.start_notify(CONTROL_CHAR, cb)
        await asyncio.sleep(2.0)

        print(f"\n=== ROUND 1: assert GPS + start navigation ===", flush=True)
        # Tell the dash where we are
        await write_msg(client, WRITE_CHAR, "sendLocation", msg_send_location("Test Street"))
        await asyncio.sleep(0.5)
        await write_msg(client, WRITE_CHAR, "sendElevationAndPond", msg_send_elevation_and_pond(17, 17, 17))
        await asyncio.sleep(0.5)
        # Active navigation update — 500m to next turn, then 5km path total
        await write_msg(client, WRITE_CHAR, "getNaviInfo (act:3)",
                        msg_get_navi_info(icon=1, next_road="Main Street",
                                         cur_retain_distance="500", cur_unittype=0,
                                         path_retain_distance="5000", path_cur_unittype=0,
                                         cur_retain_time=60, remain_time=600, retain_rate=10))
        await asyncio.sleep(0.5)
        # And explicitly request mirror on
        await write_msg(client, WRITE_CHAR, "requestMirrorStatus on", msg_request_mirror_on())
        await asyncio.sleep(3.0)

        print(f"\n=== ROUND 2: continuous nav updates (simulate riding) ===", flush=True)
        for i in range(10):
            await write_msg(client, WRITE_CHAR, f"navUpdate#{i}",
                          msg_get_navi_info(icon=1, next_road="Main Street",
                                           cur_retain_distance=str(500 - i * 50), cur_unittype=0,
                                           path_retain_distance=str(5000 - i * 50), path_cur_unittype=0,
                                           cur_retain_time=max(60 - i * 6, 0), remain_time=max(600 - i * 6, 0),
                                           retain_rate=10 + i))
            await asyncio.sleep(1.0)

        print(f"\n=== holding 30s to watch for late activity ===", flush=True)
        await asyncio.sleep(30)
        print(f"\nDone. Total non-altitude notifications: {1 if saw_new_msg['saw'] else 0}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
