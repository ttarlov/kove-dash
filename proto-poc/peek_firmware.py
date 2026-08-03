#!/usr/bin/env python3
"""Capture the full 1030-byte firmware response from the dash."""
import socket, struct, sys, time

with socket.socket() as srv:
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", 17818))
    srv.listen(1)
    print("listening 17818", flush=True)
    conn, addr = srv.accept()
    print(f"connected {addr}", flush=True)
    conn.sendall(bytes([0x01, 0x01, 0x00, 0x00, 0x00, 0x00]))
    head = b""
    while len(head) < 6:
        head += conn.recv(6 - len(head))
    t, s = head[0], head[1]
    plen = struct.unpack(">I", head[2:6])[0]
    print(f"got header type=0x{t:02x} sub=0x{s:02x} payload_len={plen}", flush=True)
    payload = b""
    while len(payload) < plen:
        payload += conn.recv(plen - len(payload))
    # Decode the firmware string field
    text = payload.split(b"\x00", 1)[0].decode("ascii", errors="replace")
    print(f"FIRMWARE STRING ({len(text)} chars):")
    print(text)
    print("---")
    contains_tuc = "_TUC=" in text
    print(f"contains '_TUC=': {contains_tuc}")
    if contains_tuc:
        idx = text.index("_TUC=")
        print(f"  TUC section: {text[idx:idx+30]!r}")
    conn.close()
