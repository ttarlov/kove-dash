---
name: Hardware report
about: Report a Kove dash unit — firmware, BLE, protocol family. Helps build the compatibility map.
title: "[hw] <model> — firmware <SV=...>"
labels: hardware-report
---

**Dash model / bike:** (e.g. Kove 450 Rally, 2022)

**Firmware string:** (from the dash About/Settings screen, e.g. `...SV=3.0.4`)

**BLE scan — service UUIDs you see:**
- (e.g. `0000e0ff-3c17-d293-8e48-14fe2e4da212` → SiQi/ThinkerRide family)
- (e.g. `0000aaa0-...` → Eryanet family)

**Dash Wi-Fi AP:**
- SSID prefix: (e.g. `CQKY_...`)
- Gateway IP when it's an AP: (`192.168.10.1` = SiQi-era, `192.168.43.1` = Eryanet-era)

**Companion app it pairs with:** (ThinkerRide / GreenTrip / KOVE / other)

**Anything else** (BLE traffic observed, projection behavior, surprises):
