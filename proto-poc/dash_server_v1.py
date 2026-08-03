#!/usr/bin/env python3
"""
v0b: Pretend to be the ThinkerRide phone-side server.
The dash is the TCP CLIENT; it dials into us. We listen on all five known
ports and replay enough of the OEM bootstrap to keep the dash happy and
display a test frame.

Architecture confirmed from decompiled ThinkerRide APK:
- Phone is server; dash is client.
- Five ports: 17818 (device control), 18888 (dvr), 19000 (ota),
  15456 (projection nav), 15457 (projection heart).
- 17818 wire format: 6-byte header [type:u8][subtype:u8][payload_len:u32_be]
  followed by payload_len bytes of payload.
- 15456 wire format: 69-byte handshake [flag:u8][platform:64B][width:u16_be][height:u16_be]
  then raw H.264 NALU bytes.

Usage:
    python3 dash_server.py [--frame assets/test_frame.h264] [--duration 120]
"""
import argparse
import json
import socket
import struct
import threading
import time
from datetime import datetime
from pathlib import Path


# from DeviceMsgFactory in the OEM APK
REQ_FIRMWARE_VERSION = bytes([0x01, 0x01, 0x00, 0x00, 0x00, 0x00])
REQ_MAC              = bytes([0x01, 0x11, 0x00, 0x00, 0x00, 0x00])
REQ_PRODUCT_TYPE     = bytes([0x01, 0x0E, 0x00, 0x00, 0x00, 0x00])
REQ_LANGUAGE         = bytes([0x01, 0x14, 0x00, 0x00, 0x00, 0x00])
HEARTBEAT            = bytes([0x02, 0x01, 0x00, 0x00, 0x00, 0x00])
REQ_MIRROR_STATUS_BIN = bytes([0x05, 0x34, 0x00, 0x00, 0x00, 0x00])  # WifiMessage.requestMirrorStatus
REQ_RECORD_STATUS    = bytes([0x05, 0x31, 0x00, 0x00, 0x00, 0x00])   # WifiMessage.requestRecordVideoStatus
# MsgFactory.sendDeviceType — announces device type=2 (probably "phone client")
SEND_DEVICE_TYPE     = bytes([0x01, 0x17, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x02])


def build_link_info(nickname: str = "kove-hack") -> bytes:
    """type=0x01 sub=0x0C, 256-byte UTF-8 nickname payload, big-endian length."""
    name_bytes = nickname.encode("utf-8")[:256]
    payload = name_bytes.ljust(256, b"\x00")
    return bytes([0x01, 0x0C]) + struct.pack(">I", 256) + payload


def build_json_msg(obj: dict) -> bytes:
    """Wrap a JSON object in the device-channel envelope:
    [0xEE 0xFD][len:4_be][utf8_json_bytes][0xFF tail]
    Matches DeviceMsgFactory.generateByteData() in OEM."""
    body = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    return bytes([0xEE, 0xFD]) + struct.pack(">I", len(body)) + body + bytes([0xFF])


def build_navi_info(distance_m: int = 500, street: str = "Main Street",
                    icon_id: int = 1, time_s: int = 30) -> bytes:
    """type=0x03 sub=0x01, 268-byte structured nav payload.
    Format from DeviceMsgFactory.sendNaviInfo(int, String, int, int):
      [type:1=0x03][sub:1=0x01][payload_len:4_be=268]
      [int_be:4=distance][street_bytes_256B_padded][int_be:4=icon][int_be:4=time]
    Total: 274 bytes (6 header + 268 payload)
    """
    street_bytes = street.encode("utf-8")[:256].ljust(256, b"\x00")
    payload = (
        struct.pack(">I", distance_m)
        + street_bytes
        + struct.pack(">I", icon_id)
        + struct.pack(">I", time_s)
    )
    assert len(payload) == 268, f"navi payload is {len(payload)}B, want 268"
    return bytes([0x03, 0x01]) + struct.pack(">I", 268) + payload

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


def log(msg: str):
    print(f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {msg}", flush=True)


def hex_short(b: bytes, n: int = 32) -> str:
    h = b[:n].hex()
    paired = " ".join(h[i:i+2] for i in range(0, len(h), 2))
    return paired + (" …" if len(b) > n else "")


def parse_header(buf: bytes) -> tuple[int, int, int]:
    """Return (type, subtype, payload_len) from a 6-byte header."""
    if len(buf) != 6:
        raise ValueError("header must be 6 bytes")
    msg_type = buf[0] & 0xFF
    subtype = buf[1] & 0xFF
    # ByteUtils.int2Bytes appears big-endian in Java code; confirm later if needed
    payload_len = struct.unpack(">I", buf[2:6])[0]
    return msg_type, subtype, payload_len


def serve_device_control(conn: socket.socket, addr, stop: threading.Event):
    """Handle the 17818 channel: send bootstrap, exchange heartbeats."""
    log(f"17818 [{addr}] starting device-control loop")
    last_send = 0.0
    state = "want_firmware"
    extras_sent = False  # send link-info + product-type once after we hit heartbeat
    try:
        conn.settimeout(0.5)
        while not stop.is_set():
            # Periodic sends based on state
            now = time.monotonic()
            if now - last_send >= 2.0:
                if state == "want_firmware":
                    conn.sendall(REQ_FIRMWARE_VERSION)
                    log(f"17818 -> request firmware version  01 01 00 00 00 00")
                elif state == "want_mac":
                    conn.sendall(REQ_MAC)
                    log(f"17818 -> request MAC               01 11 00 00 00 00")
                else:
                    if not extras_sent:
                        # Exact OEM sequence from DeviceWrapper.onDeviceConnection()
                        # queryActivateStatus → requestFirmwareVersion (already done) → sendDeviceType → sendLinkInfo → sendNaviInfo
                        tucs = build_json_msg({"msg_id": 27, "func": "TUC", "act": "GET"})
                        conn.sendall(tucs)
                        log(f"17818 -> queryActivateStatus JSON ({len(tucs)}B)")
                        conn.sendall(SEND_DEVICE_TYPE)
                        log(f"17818 -> sendDeviceType            01 17 00 00 00 04 00 00 00 02")
                        link = build_link_info("kove-hack")
                        conn.sendall(link)
                        log(f"17818 -> sendLinkInfo (262B)         01 0C 00 00 01 00 + 256-byte nickname")
                        # Match the OEM args EXACTLY: sendNaviInfo(1, "123", 2, 3)
                        navi = build_navi_info(distance_m=1, street="123", icon_id=2, time_s=3)
                        conn.sendall(navi)
                        log(f"17818 -> sendNaviInfo (274B) ARGS=(1,'123',2,3) per OEM")
                        extras_sent = True
                    conn.sendall(HEARTBEAT)
                    log(f"17818 -> heartbeat                 02 01 00 00 00 00")
                last_send = now

            # Read whatever the dash sends
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
                log(f"17818 <- type=0x{t:02x} sub=0x{s:02x} len={plen}"
                    + (f"  payload={hex_short(payload)}" if payload else ""))

                # State transitions per OEM behavior.
                # Response subtypes differ from request subtypes: dash answers
                # type=0x01 sub=0x02 for firmware, sub=0x11+something or sub=0x12 for MAC.
                # Progress on type=0x01 with any payload — we got *some* response.
                if t == 0x01 and plen > 0:
                    if state == "want_firmware":
                        ascii_preview = payload[:48].decode("ascii", errors="replace").strip()
                        log(f"17818 <- FIRMWARE VERSION RESPONSE (sub=0x{s:02x}): {ascii_preview!r} ... progressing → want_mac")
                        state = "want_mac"
                    elif state == "want_mac":
                        log(f"17818 <- MAC RESPONSE (sub=0x{s:02x}): {payload.hex()} ... progressing → heartbeat")
                        state = "heartbeat"
                    elif state == "heartbeat":
                        log(f"17818 <- type=0x01 sub=0x{s:02x} info payload (heartbeat mode)")
                elif t == 0x02 and s == 0x01:
                    pass  # peer heartbeat — already logged above; suppress here
            except socket.timeout:
                continue
    except Exception as e:
        log(f"17818 [{addr}] ERROR {type(e).__name__}: {e}")
    finally:
        try: conn.close()
        except Exception: pass
        log(f"17818 [{addr}] closed")


def serve_projection_nav(conn: socket.socket, addr, frame_bytes: bytes,
                         width: int, height: int, stop: threading.Event):
    """Handle the 15456 channel: send handshake + H.264 frame."""
    log(f"15456 [{addr}] dash connected for projection. Sending handshake + frame.")
    handshake = bytes([0x00]) + PLATFORM + struct.pack(">HH", width, height)
    assert len(handshake) == HANDSHAKE_LEN
    try:
        conn.settimeout(5.0)
        conn.sendall(handshake)
        log(f"15456 -> handshake (69B): {hex_short(handshake)}")
        # Send the frame repeatedly so the dash has continuous keyframes
        for i in range(50):
            if stop.is_set():
                break
            conn.sendall(frame_bytes)
            if i == 0:
                log(f"15456 -> frame ({len(frame_bytes)}B) — image should appear NOW")
            time.sleep(0.033)  # ~30 fps
        # Then hold the socket open and see if the dash sends anything back
        conn.settimeout(0.5)
        while not stop.is_set():
            try:
                data = conn.recv(4096)
                if not data:
                    log(f"15456 [{addr}] dash closed")
                    return
                log(f"15456 <- {len(data)}B from dash: {hex_short(data)}")
            except socket.timeout:
                continue
    except Exception as e:
        log(f"15456 [{addr}] ERROR {type(e).__name__}: {e}")
    finally:
        try: conn.close()
        except Exception: pass
        log(f"15456 [{addr}] closed")


def serve_passive(port: int, label: str, conn: socket.socket, addr,
                  stop: threading.Event):
    """For ports we don't have a smart handler for yet — just log incoming bytes."""
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
                width: int, height: int, stop: threading.Event):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((bind, port))
            srv.listen(2)
            srv.settimeout(1.0)
            log(f"port {port} ({label}) LISTENING on {bind}")
            while not stop.is_set():
                try:
                    conn, addr = srv.accept()
                except socket.timeout:
                    continue
                log(f"!!! port {port} ({label}) CONNECTION from {addr}")
                if port == 17818:
                    t = threading.Thread(target=serve_device_control,
                                         args=(conn, addr, stop), daemon=True)
                elif port == 15456:
                    t = threading.Thread(target=serve_projection_nav,
                                         args=(conn, addr, frame_bytes, width, height, stop),
                                         daemon=True)
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
    p.add_argument("--duration", type=int, default=120)
    args = p.parse_args()

    frame_bytes = args.frame.read_bytes() if args.frame.exists() else b""
    log(f"frame asset: {args.frame} ({len(frame_bytes)} bytes)")
    log(f"target resolution: {args.width}x{args.height}")

    stop = threading.Event()
    threads = []
    for port, label in PORTS.items():
        t = threading.Thread(target=accept_loop,
                             args=(port, label, args.bind, frame_bytes,
                                   args.width, args.height, stop),
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
