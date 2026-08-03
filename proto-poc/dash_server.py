#!/usr/bin/env python3
"""
v0c: Full OEM bootstrap replay + TUC activation forge.

Tries to make the dash dial back to port 15456 (projection) by replicating
the complete OEM bootstrap sequence and forging TUC (Unique Code) activation
state on the dash side.

Sources from decompiled ThinkerRide APK (oversea.whbluestar.thinkerride):
- DeviceWrapper.onDeviceConnection (DeviceWrapper.java:283-300):
    queryActivateStatus -> requestFirmwareVersion -> sendDeviceType ->
    sendLinkInfo -> (if in nav) sendNaviInfo
- DeviceWrapper.onDeviceReplyFirmwareVersion (lines 311-368):
    requestProductType -> requestMac -> ... -> queryDevicePlayerVoiceStatus ->
    queryInsideNaviStatus -> (if TUC enabled) requestUpdateInfo(0)
- UnicodeHelper.toWifiBytes (line 180-192): 0xEE 0xFD <len_be:4> <utf8_json> 0xFF
- DeviceMsgFactory.generateByteData (line 32-42): same framing
- TucUtil.isEffectiveTuc (com/thinkerride/tbox/util/TucUtil.java:14-16):
    NOT empty AND NOT "F" AND length >= 16. That's the entire check.

TUC FORGE THEORY:
- Dash's activation state is `tucs == 1 AND tuc passes isEffectiveTuc`.
- Phone sends two JSON writes that SET this state on the dash:
    {"msg_id":27,"func":"TUC","act":"SAVE","tuc":"<16+ chars>"}
    {"msg_id":27,"func":"TUC","act":"STATUS","tucs":1}
- Decompiled source shows no signature, no challenge/response, no MAC check.
  Dash appears to trust whatever the phone writes.
- If the dash flips its internal tucs flag to 1, projection gate
  (ProjectionService.isAllowShowProjection -> isActivate) opens.

Usage:
    python3 dash_server.py [--frame assets/test_frame.h264] [--duration 600]
                           [--bind 0.0.0.0] [--no-forge]
"""
import argparse
import json
import socket
import struct
import threading
import time
from datetime import datetime
from pathlib import Path


# ---------------------------------------------------------------------------
# Wire constants — exact bytes from decompiled OEM
# ---------------------------------------------------------------------------

# Binary messages on 17818: [type:u8][subtype:u8][payload_len:u32_be][payload]
REQ_FIRMWARE_VERSION = bytes([0x01, 0x01, 0x00, 0x00, 0x00, 0x00])
REQ_PRODUCT_TYPE     = bytes([0x01, 0x0E, 0x00, 0x00, 0x00, 0x00])
REQ_MAC              = bytes([0x01, 0x11, 0x00, 0x00, 0x00, 0x00])
REQ_LANGUAGE         = bytes([0x01, 0x14, 0x00, 0x00, 0x00, 0x00])
SEND_DEVICE_TYPE     = bytes([0x01, 0x17, 0x00, 0x00, 0x00, 0x04,
                              0x00, 0x00, 0x00, 0x02])  # value=2 (phone)
HEARTBEAT            = bytes([0x02, 0x01, 0x00, 0x00, 0x00, 0x00])

# A spoofable TUC: any non-"F" string of length >= 16 passes TucUtil.isEffectiveTuc.
FORGED_TUC = "KOVE450RHACK" + "0123"  # 16 chars exactly

PORTS = {
    17818: "device-control",
    18888: "dvr",
    19000: "ota",
    15456: "projection-nav",
    15457: "projection-heart",
}

HANDSHAKE_LEN = 69
PLATFORM = b"android".ljust(64, b"\x00")
DEFAULT_WIDTH = 1280
DEFAULT_HEIGHT = 640


# ---------------------------------------------------------------------------
# Wire helpers
# ---------------------------------------------------------------------------

def build_link_info(nickname: str = "kove-hack") -> bytes:
    """type=0x01 sub=0x0C, 256-byte UTF-8 nickname payload."""
    name_bytes = nickname.encode("utf-8")[:256]
    payload = name_bytes.ljust(256, b"\x00")
    return bytes([0x01, 0x0C]) + struct.pack(">I", 256) + payload


def build_json_msg(obj: dict) -> bytes:
    """Wrap a JSON object in the device-channel envelope:
    [0xEE 0xFD][len:4_be][utf8_json][0xFF tail]
    Matches DeviceMsgFactory.generateByteData() and UnicodeHelper.toWifiBytes()."""
    body = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    return bytes([0xEE, 0xFD]) + struct.pack(">I", len(body)) + body + bytes([0xFF])


def build_navi_info(distance_m: int = 500, street: str = "Pearl Street",
                    icon_id: int = 1, time_s: int = 30) -> bytes:
    """type=0x03 sub=0x01, 268-byte structured nav payload."""
    street_bytes = street.encode("utf-8")[:256].ljust(256, b"\x00")
    payload = (
        struct.pack(">I", distance_m)
        + street_bytes
        + struct.pack(">I", icon_id)
        + struct.pack(">I", time_s)
    )
    assert len(payload) == 268
    return bytes([0x03, 0x01]) + struct.pack(">I", 268) + payload


def log(msg: str):
    print(f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {msg}", flush=True)


def hex_short(b: bytes, n: int = 32) -> str:
    h = b[:n].hex()
    paired = " ".join(h[i:i+2] for i in range(0, len(h), 2))
    return paired + (" …" if len(b) > n else "")


def parse_header(buf: bytes) -> tuple[int, int, int]:
    msg_type = buf[0] & 0xFF
    subtype = buf[1] & 0xFF
    payload_len = struct.unpack(">I", buf[2:6])[0]
    return msg_type, subtype, payload_len


# ---------------------------------------------------------------------------
# 17818 handler — full OEM bootstrap + TUC forge
# ---------------------------------------------------------------------------

def serve_device_control(conn: socket.socket, addr, stop: threading.Event,
                          forge_tuc: bool, nickname: str):
    """Replicates the exact OEM bootstrap from DeviceWrapper.onDeviceConnection
    and onDeviceReplyFirmwareVersion, then heartbeats."""
    log(f"17818 [{addr}] starting device-control loop (forge_tuc={forge_tuc})")
    conn.settimeout(0.5)

    def send(label: str, data: bytes):
        try:
            conn.sendall(data)
            log(f"17818 -> {label}  ({len(data)}B)")
        except Exception as e:
            log(f"17818 -> {label}  SEND FAIL: {e}")

    # --- Stage 1: onDeviceConnection() — fires immediately on TCP accept ---
    # Per DeviceWrapper.java:290-298, this is the exact order:
    send("queryActivateStatus  msg_id=27 func=TUC act=GET",
         build_json_msg({"msg_id": 27, "func": "TUC", "act": "GET"}))
    send("requestFirmwareVersion  01 01", REQ_FIRMWARE_VERSION)
    send("sendDeviceType=2  01 17 + len4 + value2", SEND_DEVICE_TYPE)
    send(f"sendLinkInfo  01 0C + 256B nick='{nickname}'",
         build_link_info(nickname))

    # --- Stage 1.5: TUC FORGE (the experimental piece) ---
    if forge_tuc:
        log("17818 *** FORGING TUC ACTIVATION ***")
        send(f"TUC SAVE  tuc='{FORGED_TUC}' (len={len(FORGED_TUC)})",
             build_json_msg({"msg_id": 27, "func": "TUC",
                             "act": "SAVE", "tuc": FORGED_TUC}))
        time.sleep(0.2)
        send("TUC STATUS  tucs=1 (claim activated)",
             build_json_msg({"msg_id": 27, "func": "TUC",
                             "act": "STATUS", "tucs": 1}))
        time.sleep(0.2)
        # NEW (2026-05-15 deep trace): msg_id 50 = sendActivateVehicle(BID).
        # Theory: dash refuses to dial 15456 until it sees BID indicating
        # cloud account binding. Pure speculation that dash accepts any string.
        send("BID FORGE msg_id=50 (cloud-bound claim)",
             build_json_msg({"msg_id": 50, "BID": "FAKE1234567890ABCDEF"}))
        time.sleep(0.2)
        # Re-query so the dash echoes back its current activation state
        send("queryActivateStatus  (verify forge took)",
             build_json_msg({"msg_id": 27, "func": "TUC", "act": "GET"}))

    # --- Stage 2: state machine waits for dash firmware-version reply ---
    # then fires the post-reply bootstrap per DeviceWrapper.java:316-360.
    fired_post_firmware = False
    last_heartbeat = 0.0

    while not stop.is_set():
        now = time.monotonic()
        if now - last_heartbeat >= 2.0:
            try:
                conn.sendall(HEARTBEAT)
            except Exception as e:
                log(f"17818 heartbeat send failed: {e}")
                return
            last_heartbeat = now

        try:
            head = b""
            while len(head) < 6 and not stop.is_set():
                chunk = conn.recv(6 - len(head))
                if not chunk:
                    log(f"17818 [{addr}] dash closed connection")
                    return
                head += chunk
            if len(head) < 6:
                continue
            t, s, plen = parse_header(head)
            payload = b""
            while len(payload) < plen and not stop.is_set():
                chunk = conn.recv(min(4096, plen - len(payload)))
                if not chunk:
                    log(f"17818 [{addr}] dash closed mid-payload")
                    return
                payload += chunk

            # Try to interpret the response
            if t == 0xEE and s == 0xFD:
                # JSON envelope (won't actually appear at byte index 0 of header
                # since our parser expects bin framing — but log defensively)
                log(f"17818 <- JSON envelope detected at header pos? {hex_short(head + payload)}")
            elif t == 0x01:
                ascii_preview = payload[:96].decode("ascii", errors="replace").strip("\x00").strip()
                log(f"17818 <- type=0x01 sub=0x{s:02x} len={plen}  ascii={ascii_preview!r}")
                # Firmware-version reply is type=0x01, subtypes vary by firmware.
                # Trigger post-firmware bootstrap once after first sizeable type=0x01.
                if not fired_post_firmware and plen >= 8:
                    fired_post_firmware = True
                    log("17818 *** firmware reply seen, firing post-firmware bootstrap ***")
                    time.sleep(0.1)
                    send("requestProductType  01 0E", REQ_PRODUCT_TYPE)
                    send("requestMac          01 11", REQ_MAC)
                    time.sleep(0.1)
                    send("queryDevicePlayerVoiceStatus  INSIDENAVI query=2",
                         build_json_msg({"msg_id": 27, "func": "INSIDENAVI", "query": 2}))
                    send("queryInsideNaviStatus         INSIDENAVI query=1",
                         build_json_msg({"msg_id": 27, "func": "INSIDENAVI", "query": 1}))
            elif t == 0x02 and s == 0x01:
                pass  # peer heartbeat, silent
            elif t == 0x03 and s == 0x0D:
                log("17818 <- onCarEndNavi  03 0D")
            else:
                log(f"17818 <- type=0x{t:02x} sub=0x{s:02x} len={plen}  hex={hex_short(payload)}")
        except socket.timeout:
            continue
        except Exception as e:
            log(f"17818 [{addr}] ERROR {type(e).__name__}: {e}")
            return


# ---------------------------------------------------------------------------
# Other port handlers (unchanged from v1)
# ---------------------------------------------------------------------------

def serve_projection_nav(conn: socket.socket, addr, frame_bytes: bytes,
                         width: int, height: int, stop: threading.Event):
    log(f"15456 [{addr}] *** DASH DIALED IN — projection trigger WORKED ***")
    handshake = bytes([0x00]) + PLATFORM + struct.pack(">HH", width, height)
    assert len(handshake) == HANDSHAKE_LEN
    try:
        conn.settimeout(5.0)
        conn.sendall(handshake)
        log(f"15456 -> handshake (69B): {hex_short(handshake)}")
        if frame_bytes:
            i = 0
            while not stop.is_set():
                conn.sendall(frame_bytes)
                if i == 0:
                    log(f"15456 -> first frame ({len(frame_bytes)}B), looping forever")
                elif i % 300 == 0:
                    log(f"15456 -> frame #{i} sent")
                i += 1
                time.sleep(0.033)
            return
        conn.settimeout(0.5)
        while not stop.is_set():
            try:
                data = conn.recv(4096)
                if not data:
                    log(f"15456 [{addr}] dash closed")
                    return
                log(f"15456 <- {len(data)}B: {hex_short(data)}")
            except socket.timeout:
                continue
    except Exception as e:
        log(f"15456 [{addr}] ERROR {type(e).__name__}: {e}")
    finally:
        try: conn.close()
        except Exception: pass
        log(f"15456 [{addr}] closed")


def serve_projection_heart(conn: socket.socket, addr, stop: threading.Event):
    log(f"15457 [{addr}] *** DASH DIALED projection-heart ***")
    last = 0.0
    try:
        conn.settimeout(0.5)
        while not stop.is_set():
            now = time.monotonic()
            if now - last >= 0.45:
                try:
                    conn.sendall(HEARTBEAT)
                    last = now
                except Exception as e:
                    log(f"15457 heart send failed: {e}")
                    return
            try:
                data = conn.recv(4096)
                if not data:
                    log(f"15457 [{addr}] closed"); return
                log(f"15457 <- {len(data)}B: {hex_short(data)}")
            except socket.timeout:
                continue
    except Exception as e:
        log(f"15457 [{addr}] ERROR {e}")
    finally:
        try: conn.close()
        except Exception: pass


def serve_passive(port: int, label: str, conn: socket.socket, addr,
                  stop: threading.Event):
    log(f"{port} [{addr}] ({label}) passive — just logging")
    total = 0
    try:
        conn.settimeout(0.5)
        while not stop.is_set():
            try:
                data = conn.recv(4096)
                if not data:
                    log(f"{port} [{addr}] closed after {total}B")
                    return
                total += len(data)
                log(f"{port} <- {len(data)}B (total {total}): {hex_short(data)}")
            except socket.timeout:
                continue
    except Exception as e:
        log(f"{port} [{addr}] ERROR {type(e).__name__}: {e}")
    finally:
        try: conn.close()
        except Exception: pass


def accept_loop(port: int, label: str, bind: str, frame_bytes: bytes,
                width: int, height: int, stop: threading.Event,
                forge_tuc: bool, nickname: str):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((bind, port))
            srv.listen(2)
            srv.settimeout(1.0)
            log(f"port {port:5d} ({label}) LISTENING on {bind}")
            while not stop.is_set():
                try:
                    conn, addr = srv.accept()
                except socket.timeout:
                    continue
                log(f"!!! port {port} ({label}) CONNECTION from {addr}")
                if port == 17818:
                    t = threading.Thread(target=serve_device_control,
                                         args=(conn, addr, stop, forge_tuc, nickname),
                                         daemon=True)
                elif port == 15456:
                    t = threading.Thread(target=serve_projection_nav,
                                         args=(conn, addr, frame_bytes, width, height, stop),
                                         daemon=True)
                elif port == 15457:
                    t = threading.Thread(target=serve_projection_heart,
                                         args=(conn, addr, stop), daemon=True)
                else:
                    t = threading.Thread(target=serve_passive,
                                         args=(port, label, conn, addr, stop), daemon=True)
                t.start()
    except OSError as e:
        log(f"port {port} BIND ERROR: {e}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--frame", type=Path, default=Path("assets/test_frame.h264"))
    p.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    p.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    p.add_argument("--duration", type=int, default=600)
    p.add_argument("--nickname", default="kove-hack")
    p.add_argument("--no-forge", action="store_true",
                   help="Skip the TUC activation forge step (control test).")
    args = p.parse_args()

    frame_bytes = args.frame.read_bytes() if args.frame.exists() else b""
    log(f"frame asset: {args.frame} ({len(frame_bytes)} bytes)")
    log(f"target resolution: {args.width}x{args.height}")
    log(f"nickname: {args.nickname}")
    log(f"tuc forge: {'OFF (control)' if args.no_forge else f'ON (tuc={FORGED_TUC!r})'}")

    stop = threading.Event()
    threads = []
    for port, label in PORTS.items():
        t = threading.Thread(target=accept_loop,
                             args=(port, label, args.bind, frame_bytes,
                                   args.width, args.height, stop,
                                   not args.no_forge, args.nickname),
                             daemon=True)
        t.start()
        threads.append(t)

    log(f"all sockets up. running for {args.duration}s. Ctrl-C to stop early.")
    try:
        time.sleep(args.duration)
    except KeyboardInterrupt:
        pass
    log("stopping ...")
    stop.set()
    for t in threads:
        t.join(timeout=2)
    log("done")


if __name__ == "__main__":
    main()
