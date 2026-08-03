#!/usr/bin/env python3
"""Connect to the dash via BLE, discover GATT services/characteristics, log
notifications. Run alongside dash_server.py so we can see whether the BT
connection alone causes the dash to dial port 15456."""
import asyncio
import sys
from bleak import BleakClient, BleakScanner


DASH_NAME = "CQKY_XXXXXXXXX"


async def main():
    print(f"scanning for {DASH_NAME}...", flush=True)
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if not dev:
        print("dash not found via BLE scan", file=sys.stderr)
        sys.exit(1)
    print(f"found: {dev.address}  rssi=…", flush=True)

    async with BleakClient(dev) as client:
        print(f"connected: {client.is_connected}", flush=True)
        services = client.services
        print(f"\n--- GATT services on dash ---")
        for svc in services:
            print(f"\nService {svc.uuid}  ({svc.description})")
            for ch in svc.characteristics:
                props = ",".join(ch.properties)
                print(f"  Char {ch.uuid}  props=[{props}]")
                for desc in ch.descriptors:
                    print(f"    Desc {desc.uuid}")

        # Try reading readable characteristics + subscribe to notifications
        print(f"\n--- reading + subscribing ---")
        for svc in services:
            for ch in svc.characteristics:
                if "read" in ch.properties:
                    try:
                        val = await client.read_gatt_char(ch.uuid)
                        text_preview = val.decode("ascii", errors="replace")[:40]
                        print(f"  READ  {ch.uuid}: {val.hex()}  ({text_preview!r})")
                    except Exception as e:
                        print(f"  READ  {ch.uuid}: ERROR {e}")
                if "notify" in ch.properties or "indicate" in ch.properties:
                    def make_cb(uuid):
                        def cb(_, data):
                            print(f"  NOTIF {uuid}: {data.hex()}", flush=True)
                        return cb
                    try:
                        await client.start_notify(ch.uuid, make_cb(ch.uuid))
                        print(f"  SUBSCRIBED to notifications on {ch.uuid}")
                    except Exception as e:
                        print(f"  SUBSCRIBE {ch.uuid}: ERROR {e}")

        print(f"\n--- holding connection 30s — watch for activity (and check if dash dials 15456) ---", flush=True)
        await asyncio.sleep(30)
        print("disconnecting...", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
