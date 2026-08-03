#!/usr/bin/env python3
"""Scan for BLE devices near the laptop. Look for the dash.
Dash BT MAC (from WiFi-side response): XX:XX:XX:XX:XX:XX
macOS Core Bluetooth hides real MACs behind per-host random UUIDs, so we
identify the dash by name pattern instead."""
import asyncio
from bleak import BleakScanner


async def main():
    print("scanning BLE for 10 seconds...")
    devs = await BleakScanner.discover(timeout=10.0, return_adv=True)
    print(f"\nfound {len(devs)} BLE devices:\n")
    rows = []
    for addr, (dev, adv) in devs.items():
        name = dev.name or adv.local_name or ""
        rssi = adv.rssi
        rows.append((rssi or -200, addr, name, list(adv.service_uuids or []), dict(adv.manufacturer_data or {})))
    rows.sort(reverse=True)  # strongest RSSI first
    for rssi, addr, name, suuids, mfg in rows:
        is_candidate = False
        name_lower = (name or "").lower()
        if any(s in name_lower for s in ("cqky", "kove", "siqi", "thinker", "rally", "bluestar")):
            is_candidate = True
        marker = "  <-- POSSIBLE DASH" if is_candidate else ""
        print(f"  rssi={rssi:>4}  addr={addr}  name={name!r}{marker}")
        if suuids:
            print(f"           services: {suuids}")
        if mfg:
            mfg_print = {k: v.hex() for k, v in mfg.items()}
            print(f"           mfg_data: {mfg_print}")
    if not any(name and any(s in name.lower() for s in ("cqky", "kove", "siqi", "thinker", "rally", "bluestar")) for _, _, name, _, _ in rows):
        print("\n!!! No obvious dash candidate found by name.")
        print("    The dash might advertise as something else, or BT may be off.")
        print("    Check the top-RSSI unnamed entries — those are physically close to you.")


if __name__ == "__main__":
    asyncio.run(main())
