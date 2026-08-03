#!/usr/bin/env python3
"""Interactive probe REPL for the Kove dash BLE channel.

Establishes the OEM handshake (same as bt_keepalive.py), then drops into a
prompt where you can fire individual structured-data probes against the dash
and watch ffe2/ffe3 for replies. Built for bench testing what slots the dash
actually accepts before porting winners into the Kotlin app.

Usage:
    python3 bt_probe_repl.py                  # full handshake + REPL
    python3 bt_probe_repl.py --no-handshake   # skip handshake, REPL only
    python3 bt_probe_repl.py --log probe.log  # tee everything to a log

REPL commands:
    help                              show this list
    quit | q                          disconnect & exit
    dump [n]                          show last n inbound JSON (default 20)
    clear                             clear inbound buffer
    raw <json>                        send arbitrary JSON as-is
    hex  <hex_bytes>                  write raw bytes to ffe1 (no framing)

    --- nav overlay (the Simple-Navi feeders) ---
    navi      <icon_id> <next_road> <cur_m> <path_m> <secs_to_next> [total_m]
    naviold   <icon_id> <next_road> <cur_m> <path_m> <remain_sec>
    dest      <lat> <lng> [name]
    cross     <png_path>
    cross_off
    tts       <text>

    --- environmental ---
    time      [tag]              # default tag=-1 (unsolicited)
    altitude  <m> [avg] [max]
    compass   <deg>
    weather   <code> <celsius>
    speed     <kmh>
    street    <name>
    signal    <0-5>

    --- capability / status queries ---
    scpt                         # SCPT get_scpt — capability bitmap
    inside_query                 # INSIDENAVI query=1
    voice_query                  # INSIDENAVI query=2
    version                      # msg_id=13
    product                      # msg_id=26
    car_status                   # msg_id=54

The REPL auto-responds to msg_id=10 item=4 (dash's time-sync solicitation) by
echoing msg_id=11 with the requested tag, mirroring the OEM behavior.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import struct
import sys
from collections import deque
from datetime import datetime
from pathlib import Path
from bleak import BleakClient, BleakScanner

DASH_NAME    = "CQKY_XXXXXXXXX"
WRITE_CHAR   = "0000ffe1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR  = "0000ffe2-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR = "0000ffe3-0000-1000-8000-00805f9b34fb"

FRAME_LEN = 104
CHUNK_PAYLOAD_MAX = 100

_log_file = None


def log(msg: str) -> None:
    line = f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {msg}"
    print(line, flush=True)
    if _log_file is not None:
        _log_file.write(line + "\n")
        _log_file.flush()


# ---------- framing ----------

def get_crc_code(buf: bytes) -> bytes:
    total = sum(buf) & 0xFF
    return bytes([((total & 0xF0) >> 4) | 0x80,
                  ( total & 0x0F)       | 0x80])


def byte_cat(buf: bytes) -> bytes:
    """Mirrors OEM ByteUtils.byteCat: overlays CRC over trailing NUL."""
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
        n = min(len(catted) - i, CHUNK_PAYLOAD_MAX)
        f = bytearray(FRAME_LEN)
        f[0] = 0xFE
        struct.pack_into(">H", f, 1, seq)
        f[3:3 + n] = catted[i:i + n]
        f[3 + n] = 0xFF
        frames.append(bytes(f))
        i += n
    return frames


# ---------- inbound reassembly ----------

class JsonReassembler:
    """Buffers inbound notifications and emits complete JSON objects.

    Mirrors the OEM's text-mode receive path: decode UTF-8 with replacement,
    track brace depth, emit on each balanced object.
    """

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[dict]:
        self._buf.extend(data)
        text = self._buf.decode("utf-8", errors="ignore")
        objs: list[dict] = []
        depth = 0
        start = None
        in_str = False
        esc = False
        last_end = 0
        for i, ch in enumerate(text):
            if esc:
                esc = False
                continue
            if in_str:
                if ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                if depth == 0:
                    start = i
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0 and start is not None:
                    try:
                        objs.append(json.loads(text[start:i + 1]))
                        last_end = i + 1
                    except json.JSONDecodeError:
                        pass
                    start = None
        # drop fully consumed prefix; if anything was emitted, clear buffer
        if objs:
            self._buf.clear()
        return objs


# ---------- probe builders ----------

def b_time(tag: int = -1) -> dict:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return {"msg_id": 11, "time": now, "tag": tag}


def b_altitude(m: int, avg: int = 0, mx: int = 0) -> dict:
    return {"msg_id": 25, "msg_type": 9, "msg_source": 2,
            "altitude": m, "ave_altitude": avg, "max_altitude": mx,
            "pond_distance": 0.0, "pond_time": 0, "head": 0}


def b_navi(icon: int, next_road: str, cur_m: int, path_m: int,
           secs_to_next: int, total_m: int | None = None) -> dict:
    cur_str = str(cur_m) if cur_m < 1000 else f"{cur_m/1000:.1f}"
    path_str = str(path_m) if path_m < 1000 else f"{path_m/1000:.1f}"
    cur_unit = 0 if cur_m < 1000 else 1
    path_unit = 0 if path_m < 1000 else 1
    import time as _t
    eta = int(_t.time()) + secs_to_next
    if total_m and total_m > 0:
        retain_rate = max(0, min(100, int((1.0 - path_m / total_m) * 100)))
    else:
        retain_rate = 0
    return {"msg_id": 27, "func": "NAVI", "act": 3,
            "icon": icon, "next_road": next_road,
            "cur_retain_distance": cur_str, "cur_unittype": cur_unit,
            "path_retain_distance": path_str, "path_cur_unittype": path_unit,
            "cur_retain_time": secs_to_next, "remain_time": eta,
            "retain_rate": retain_rate}


def b_naviold(icon: int, next_road: str, cur_m: int, path_m: int,
              remain_sec: int) -> dict:
    return {"msg_id": 1, "icon": icon, "next_road": next_road,
            "cur_retain_distance": cur_m, "path_retain_distance": path_m,
            "remain_time": remain_sec}


def b_dest(lat: float, lng: float, name: str = "") -> dict:
    return {"msg_id": 27, "func": "NAVI", "act": 1,
            "lat": lat, "lng": lng, "dst": name}


def b_cross(png_path: str) -> dict:
    data = Path(png_path).read_bytes()
    b64 = base64.b64encode(data).decode("ascii")
    return {"msg_id": 4, "icon": b64}


def b_cross_off() -> dict:
    return {"msg_id": 4}


def b_tts(text: str) -> dict:
    return {"msg_id": 27, "func": "AUDIO", "act": "send_text", "text": text}


def b_compass(deg: float) -> dict:
    return {"msg_id": 25, "msg_type": 15, "msg_source": 2, "angle": deg}


def b_weather(code: int, celsius: int) -> dict:
    return {"msg_id": 8, "weather": code, "temperature": celsius}


def b_speed(kmh: int) -> dict:
    return {"msg_id": 22, "cur_speed": kmh}


def b_street(name: str) -> dict:
    return {"msg_id": 7, "street": name}


def b_signal(level: int) -> dict:
    return {"msg_id": 25, "msg_type": 7, "msg_source": 2, "signal": level}


def b_scpt() -> dict:
    return {"msg_id": 27, "func": "SCPT", "act": "get_scpt"}


def b_inside_query() -> dict:
    return {"msg_id": 27, "func": "INSIDENAVI", "query": 1}


def b_voice_query() -> dict:
    return {"msg_id": 27, "func": "INSIDENAVI", "query": 2}


def b_version() -> dict:
    return {"msg_id": 13}


def b_product() -> dict:
    return {"msg_id": 26}


def b_car_status() -> dict:
    return {"msg_id": 54}


# ---------- session ----------

class ProbeSession:
    def __init__(self, client: BleakClient) -> None:
        self.client = client
        self.seq = 1
        self.recent: deque[dict] = deque(maxlen=200)
        self.reassembler = JsonReassembler()
        self._spam_filter = True  # drop dash's idle telemetry spam

    async def setup_notifies(self) -> None:
        await self.client.start_notify(NOTIFY_CHAR, self._make_cb("ffe2"))
        try:
            await self.client.start_notify(CONTROL_CHAR, self._make_cb("ffe3"))
        except Exception as e:
            log(f"ffe3 notify subscribe failed (non-fatal): {e}")

    def _make_cb(self, label: str):
        def cb(_handle, data: bytes) -> None:
            hexs = " ".join(f"{b:02x}" for b in data[:24])
            objs = self.reassembler.feed(bytes(data))
            for obj in objs:
                if self._is_spam(obj):
                    continue
                self.recent.append(obj)
                log(f"<-- [{label}] {len(data)}B  JSON: {json.dumps(obj, separators=(',',':'))}")
                # auto-respond to time-sync solicitation
                if obj.get("msg_id") == 10 and obj.get("item") == 4:
                    tag = obj.get("tag", -1)
                    log(f"    [auto] dash requested time, tag={tag} — replying")
                    asyncio.create_task(self.send_json(b_time(tag), label="time(auto)"))
            if not objs and len(data) > 0:
                # log unrecognized fragments at low frequency
                log(f"<-- [{label}] {len(data)}B  HEX: {hexs}")
        return cb

    def _is_spam(self, obj: dict) -> bool:
        if not self._spam_filter:
            return False
        # dash's idle altitude=17/msg_type=17 heartbeat
        if obj.get("msg_id") == 25 and obj.get("msg_type") == 17 \
                and obj.get("altitude") == 17:
            return True
        # idle item=1 speed=0 spam
        if obj.get("msg_id") == 10 and obj.get("item") == 1 \
                and obj.get("current") == 0:
            return True
        return False

    async def send_json(self, obj: dict, label: str = "") -> None:
        seq = self.seq
        self.seq = (self.seq + 1) & 0xFFFF
        frames = build_frames(obj, seq)
        body = json.dumps(obj, separators=(",", ":"))
        tag = label or f"msg_id={obj.get('msg_id')}"
        log(f"--> {tag}  (seq={seq}, {len(frames)} frame(s))")
        log(f"    json: {body}")
        for fr in frames:
            await self.client.write_gatt_char(WRITE_CHAR, fr, response=False)
            await asyncio.sleep(0.05)

    async def send_raw(self, data: bytes) -> None:
        log(f"--> raw {len(data)}B  HEX: {' '.join(f'{b:02x}' for b in data[:24])}")
        await self.client.write_gatt_char(WRITE_CHAR, data, response=False)


# ---------- OEM handshake (mirrors bt_keepalive.py) ----------

async def run_handshake(s: ProbeSession) -> None:
    log("firing OEM handshake...")
    await s.send_json(b_version(), "requestVersionCode")
    await asyncio.sleep(2.0)
    await s.send_json({"msg_id": 24, "unique_info": "kove-probe"}, "sendLinkInfo")
    await asyncio.sleep(0.5)
    await s.send_json(b_product(), "requestProductType")
    await asyncio.sleep(0.5)
    await s.send_json(b_car_status(), "checkVehicleCurStatus")
    await asyncio.sleep(0.5)
    await s.send_json(b_voice_query(), "queryDevicePlayerVoiceStatus")
    await asyncio.sleep(0.5)
    await s.send_json(b_inside_query(), "queryInsideNaviStatus")
    await asyncio.sleep(0.5)
    log("handshake complete.")


# ---------- REPL dispatch ----------

HELP = """commands:
  help                                 show this list
  quit | q                             disconnect & exit
  dump [n]                             show last n inbound JSON (default 20)
  clear                                clear inbound buffer
  raw <json>                           send arbitrary JSON
  hex  <hex_bytes>                     write raw bytes to ffe1 (no framing)

  navi      <icon> <next_road> <cur_m> <path_m> <secs> [total_m]
  naviold   <icon> <next_road> <cur_m> <path_m> <remain_sec>
  dest      <lat> <lng> [name]
  cross     <png_path>
  cross_off
  tts       <text>

  time      [tag]
  altitude  <m> [avg] [max]
  compass   <deg>
  weather   <code> <celsius>
  speed     <kmh>
  street    <name>
  signal    <0-5>

  scpt | inside_query | voice_query | version | product | car_status
"""


async def dispatch(s: ProbeSession, line: str) -> bool:
    """Returns False to exit REPL."""
    line = line.strip()
    if not line:
        return True
    parts = line.split(None, 1)
    cmd = parts[0].lower()
    rest = parts[1] if len(parts) > 1 else ""
    args = rest.split() if rest else []

    try:
        if cmd in ("help", "?"):
            print(HELP)
        elif cmd in ("quit", "q", "exit"):
            return False
        elif cmd == "dump":
            n = int(args[0]) if args else 20
            for obj in list(s.recent)[-n:]:
                print(json.dumps(obj, separators=(",", ":")))
        elif cmd == "clear":
            s.recent.clear()
            print("cleared.")
        elif cmd == "raw":
            obj = json.loads(rest)
            await s.send_json(obj, "raw")
        elif cmd == "hex":
            data = bytes.fromhex(rest.replace(" ", ""))
            await s.send_raw(data)

        elif cmd == "navi":
            icon = int(args[0])
            next_road = args[1]
            cur_m = int(args[2])
            path_m = int(args[3])
            secs = int(args[4])
            total_m = int(args[5]) if len(args) > 5 else None
            await s.send_json(b_navi(icon, next_road, cur_m, path_m, secs, total_m),
                              "NAVI act=3")
        elif cmd == "naviold":
            icon = int(args[0]); next_road = args[1]
            cur_m = int(args[2]); path_m = int(args[3]); remain = int(args[4])
            await s.send_json(b_naviold(icon, next_road, cur_m, path_m, remain),
                              "msg_id=1 navi old")
        elif cmd == "dest":
            lat = float(args[0]); lng = float(args[1])
            name = args[2] if len(args) > 2 else ""
            await s.send_json(b_dest(lat, lng, name), "NAVI act=1")
        elif cmd == "cross":
            await s.send_json(b_cross(args[0]), "msg_id=4 cross")
        elif cmd == "cross_off":
            await s.send_json(b_cross_off(), "msg_id=4 cross OFF")
        elif cmd == "tts":
            await s.send_json(b_tts(rest), "AUDIO send_text")

        elif cmd == "time":
            tag = int(args[0]) if args else -1
            await s.send_json(b_time(tag), f"time tag={tag}")
        elif cmd == "altitude":
            m = int(args[0])
            avg = int(args[1]) if len(args) > 1 else 0
            mx = int(args[2]) if len(args) > 2 else 0
            await s.send_json(b_altitude(m, avg, mx), "altitude")
        elif cmd == "compass":
            await s.send_json(b_compass(float(args[0])), "compass")
        elif cmd == "weather":
            await s.send_json(b_weather(int(args[0]), int(args[1])), "weather")
        elif cmd == "speed":
            await s.send_json(b_speed(int(args[0])), "speed")
        elif cmd == "street":
            await s.send_json(b_street(rest), "street")
        elif cmd == "signal":
            await s.send_json(b_signal(int(args[0])), "signal")

        elif cmd == "scpt":
            await s.send_json(b_scpt(), "SCPT get_scpt")
        elif cmd == "inside_query":
            await s.send_json(b_inside_query(), "INSIDENAVI q=1")
        elif cmd == "voice_query":
            await s.send_json(b_voice_query(), "INSIDENAVI q=2")
        elif cmd == "version":
            await s.send_json(b_version(), "requestVersionCode")
        elif cmd == "product":
            await s.send_json(b_product(), "requestProductType")
        elif cmd == "car_status":
            await s.send_json(b_car_status(), "checkVehicleCurStatus")

        else:
            print(f"unknown command: {cmd!r}. try 'help'.")
    except Exception as e:
        log(f"!! dispatch error: {type(e).__name__}: {e}")
    return True


async def repl(s: ProbeSession) -> None:
    loop = asyncio.get_event_loop()
    log("=" * 70)
    log("REPL ready. Type 'help' for commands, 'q' to quit.")
    log("=" * 70)
    while True:
        try:
            line = await loop.run_in_executor(None, input, "probe> ")
        except (EOFError, KeyboardInterrupt):
            log("EOF — exiting.")
            return
        if not await dispatch(s, line):
            return


# ---------- main ----------

async def main_async(args: argparse.Namespace) -> None:
    log(f"scanning for {DASH_NAME}...")
    dev = await BleakScanner.find_device_by_name(DASH_NAME, timeout=10.0)
    if dev is None:
        log("dash not found via BLE scan — make sure bike is on and in range.")
        sys.exit(1)
    log(f"found: {dev.address}")

    async with BleakClient(dev) as client:
        log(f"connected: {client.is_connected}")
        s = ProbeSession(client)
        await s.setup_notifies()
        await asyncio.sleep(1.5)

        if not args.no_handshake:
            await run_handshake(s)
        else:
            log("skipping handshake (--no-handshake).")

        await repl(s)
        log("disconnecting...")


def main() -> None:
    global _log_file
    p = argparse.ArgumentParser(description="Kove dash BLE probe REPL")
    p.add_argument("--no-handshake", action="store_true",
                   help="Skip the OEM handshake; go straight to REPL")
    p.add_argument("--log", type=str, default=None,
                   help="Tee all output to this logfile")
    p.add_argument("--no-spam-filter", action="store_true",
                   help="Show idle dash heartbeat/spam (off by default)")
    args = p.parse_args()
    if args.log:
        _log_file = open(args.log, "a", buffering=1)
        log(f"logging to {args.log}")
    try:
        asyncio.run(main_async(args))
    finally:
        if _log_file:
            _log_file.close()


if __name__ == "__main__":
    main()
