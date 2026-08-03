#!/usr/bin/env python3
"""
v0a-pivot: Listen on ALL five known dash protocol ports simultaneously.
The phone is the TCP server, the dash dials in. Whichever port gets a
connection first tells us the bootstrap order.

Usage:
    python3 listen_all.py [--bind 0.0.0.0] [--duration 60]
"""
import argparse
import socket
import struct
import threading
import time
from datetime import datetime


PORTS = {
    17818: "device-control (primary)",
    18888: "dvr",
    19000: "ota",
    15456: "projection-nav",
    15457: "projection-heart",
}


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] {msg}", flush=True)


def hexdump(data: bytes, n: int = 64) -> str:
    h = data[:n].hex()
    return " ".join(h[i:i+2] for i in range(0, len(h), 2)) + (" …" if len(data) > n else "")


def serve_port(port: int, label: str, bind: str, stop: threading.Event):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((bind, port))
            srv.listen(1)
            srv.settimeout(1.0)
            log(f"port {port} ({label}) LISTENING")
            while not stop.is_set():
                try:
                    conn, addr = srv.accept()
                except socket.timeout:
                    continue
                log(f"!!! port {port} ({label}) CONNECTION from {addr}")
                conn.settimeout(2.0)
                total = 0
                try:
                    while not stop.is_set():
                        try:
                            data = conn.recv(4096)
                        except socket.timeout:
                            continue
                        if not data:
                            log(f"port {port} client disconnected after {total} bytes")
                            break
                        total += len(data)
                        log(f"port {port} <- {len(data)}B (total {total}): {hexdump(data)}")
                finally:
                    conn.close()
    except OSError as e:
        log(f"port {port} ERROR: {e}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--duration", type=int, default=60, help="seconds to listen before exit")
    args = p.parse_args()

    stop = threading.Event()
    threads = []
    for port, label in PORTS.items():
        t = threading.Thread(target=serve_port, args=(port, label, args.bind, stop), daemon=True)
        t.start()
        threads.append(t)

    log(f"all ports up. listening for {args.duration}s. dash should dial in on one of these.")
    try:
        time.sleep(args.duration)
    except KeyboardInterrupt:
        pass
    stop.set()
    log("stopping")
    for t in threads:
        t.join(timeout=2)
    log("done")


if __name__ == "__main__":
    main()
