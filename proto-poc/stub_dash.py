#!/usr/bin/env python3
"""
v0a: TCP stub that pretends to be the dash for bench testing.

Listens on 15456, accepts one client, parses the 69-byte handshake,
then logs all subsequent bytes as H.264 NALUs.

Usage:
    python3 stub_dash.py --port 15456
"""
import argparse
import socket
import struct
import sys


HANDSHAKE_LEN = 69
PLATFORM_FIELD_LEN = 64


def parse_handshake(data: bytes) -> dict:
    assert len(data) == HANDSHAKE_LEN, f"handshake must be {HANDSHAKE_LEN} bytes, got {len(data)}"
    flag = data[0]
    platform_field = data[1 : 1 + PLATFORM_FIELD_LEN]
    width, height = struct.unpack(">HH", data[1 + PLATFORM_FIELD_LEN : HANDSHAKE_LEN])
    platform = platform_field.rstrip(b"\x00").decode("utf-8", errors="replace")
    return {"flag": flag, "platform": platform, "width": width, "height": height, "raw_hex": data.hex()}


def find_nalus(buf: bytes) -> list[tuple[int, int]]:
    """Return list of (start_index_after_prefix, prefix_len) for Annex-B NALUs in buf."""
    positions = []
    i = 0
    while i < len(buf) - 3:
        if buf[i : i + 4] == b"\x00\x00\x00\x01":
            positions.append((i + 4, 4))
            i += 4
        elif buf[i : i + 3] == b"\x00\x00\x01":
            positions.append((i + 3, 3))
            i += 3
        else:
            i += 1
    return positions


NALU_TYPE_NAMES = {
    1: "P/B slice",
    5: "IDR slice (keyframe)",
    6: "SEI",
    7: "SPS",
    8: "PPS",
    9: "AUD",
    13: "Sequence Param Ext",
}


def describe_nalu_byte(b: int) -> str:
    nalu_type = b & 0x1F
    name = NALU_TYPE_NAMES.get(nalu_type, f"unknown({nalu_type})")
    return f"type={nalu_type:2d} ({name})"


def main() -> int:
    p = argparse.ArgumentParser(description="Stub dash listener for v0a testing")
    p.add_argument("--port", type=int, default=15456)
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--once", action="store_true", help="exit after one client disconnects")
    args = p.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((args.bind, args.port))
        srv.listen(1)
        print(f"[stub ] listening on {args.bind}:{args.port}")
        while True:
            conn, addr = srv.accept()
            with conn:
                print(f"[stub ] client connected from {addr}")
                hs = b""
                while len(hs) < HANDSHAKE_LEN:
                    chunk = conn.recv(HANDSHAKE_LEN - len(hs))
                    if not chunk:
                        print(f"[stub ] connection closed before handshake complete ({len(hs)}/{HANDSHAKE_LEN})")
                        break
                    hs += chunk
                if len(hs) == HANDSHAKE_LEN:
                    info = parse_handshake(hs)
                    print(f"[stub ] handshake OK")
                    print(f"[stub ]   flag     : 0x{info['flag']:02x}")
                    print(f"[stub ]   platform : {info['platform']!r}")
                    print(f"[stub ]   width    : {info['width']}")
                    print(f"[stub ]   height   : {info['height']}")
                    print(f"[stub ]   raw hex  : {info['raw_hex']}")
                    total = 0
                    while True:
                        chunk = conn.recv(65536)
                        if not chunk:
                            break
                        total += len(chunk)
                        nalus = find_nalus(chunk)
                        if nalus:
                            print(f"[stub ] received {len(chunk)} bytes ({total} total), NALU starts in chunk:")
                            for idx, (pos, pfx) in enumerate(nalus[:8]):
                                if pos < len(chunk):
                                    print(f"[stub ]   NALU {idx}: prefix {pfx}B, header 0x{chunk[pos]:02x} → {describe_nalu_byte(chunk[pos])}")
                        else:
                            print(f"[stub ] received {len(chunk)} bytes ({total} total), no NALU start in chunk")
                    print(f"[stub ] client closed. {total} bytes of frame data total.")
            if args.once:
                break
    return 0


if __name__ == "__main__":
    sys.exit(main())
