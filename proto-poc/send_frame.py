#!/usr/bin/env python3
"""
v0a: Kove dash protocol proof — send a single H.264 frame to the dash.

Protocol (from ThinkerRide APK decompilation):
- TCP to dash_ip:15456
- 69-byte handshake: [flag:1][platform_utf8_padded:64][width_be_u16:2][height_be_u16:2]
- Then raw H.264 NALU bytes (Annex-B start codes 00 00 00 01)

Usage:
    python3 send_frame.py --dash 192.168.10.1 --frame assets/test_frame.h264
    python3 send_frame.py --dash 127.0.0.1 --port 15456 --frame assets/test_frame.h264   # against stub
"""
import argparse
import socket
import struct
import sys
from pathlib import Path


HANDSHAKE_LEN = 69
DEFAULT_PORT = 15456
DEFAULT_WIDTH = 1280
DEFAULT_HEIGHT = 640
PLATFORM = b"android"
PLATFORM_FIELD_LEN = 64
HANDSHAKE_FLAG = 0x00  # unknown byte — start with 0, refine via capture if dash rejects


def build_handshake(width: int, height: int, flag: int = HANDSHAKE_FLAG) -> bytes:
    if not (0 <= flag <= 0xFF):
        raise ValueError("flag must fit in one byte")
    platform_field = PLATFORM.ljust(PLATFORM_FIELD_LEN, b"\x00")
    packet = bytes([flag]) + platform_field + struct.pack(">HH", width, height)
    assert len(packet) == HANDSHAKE_LEN, f"handshake is {len(packet)} bytes, want {HANDSHAKE_LEN}"
    return packet


def main() -> int:
    p = argparse.ArgumentParser(description="v0a Kove dash frame sender")
    p.add_argument("--dash", required=True, help="dash IP (e.g. 192.168.10.1) or 127.0.0.1 for stub")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    p.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    p.add_argument("--frame", type=Path, required=True, help="path to raw H.264 NALU file")
    p.add_argument("--repeat", type=int, default=1, help="how many times to send the frame")
    p.add_argument("--hold", type=float, default=2.0, help="seconds to hold the socket open after sending")
    args = p.parse_args()

    frame_bytes = args.frame.read_bytes()
    print(f"[client] frame: {args.frame} ({len(frame_bytes)} bytes)")
    if not frame_bytes.startswith(b"\x00\x00\x00\x01") and not frame_bytes.startswith(b"\x00\x00\x01"):
        print(f"[client] WARNING: frame does not start with an Annex-B NALU start code", file=sys.stderr)

    handshake = build_handshake(args.width, args.height)
    print(f"[client] handshake ({len(handshake)} bytes): {handshake.hex()}")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(5.0)
        print(f"[client] connecting to {args.dash}:{args.port} ...")
        s.connect((args.dash, args.port))
        print(f"[client] connected")
        s.sendall(handshake)
        print(f"[client] handshake sent")
        for i in range(args.repeat):
            s.sendall(frame_bytes)
            print(f"[client] frame {i+1}/{args.repeat} sent ({len(frame_bytes)} bytes)")
        if args.hold > 0:
            print(f"[client] holding socket open {args.hold}s ...")
            s.settimeout(args.hold)
            try:
                while True:
                    data = s.recv(4096)
                    if not data:
                        break
                    print(f"[client] <- dash: {len(data)} bytes  {data[:32].hex()}{'…' if len(data) > 32 else ''}")
            except socket.timeout:
                pass
    print(f"[client] done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
