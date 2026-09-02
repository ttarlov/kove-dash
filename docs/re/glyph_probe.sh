#!/usr/bin/env bash
# Glyph probe for the Kove dash legacy TBT icon space.
# Steps icon codes through testNav (NavTestReceiver, debug build only), showing the
# code number as the road name so you can read off the dash which code drew which glyph.
#
# The dash (SV=3.0.4) renders the LEGACY msg_id:1 frame, so the glyph you see maps to
# the code we pass here. Paced slow so the multi-frame message reassembles on a quiet link.
#
# Usage:  ./glyph_probe.sh [START] [END] [DWELL_SEC] [STARTM]
#   defaults: 1 48 7 500
#   single code:  ./glyph_probe.sh 25 25 0
set -euo pipefail

START="${1:-1}"
END="${2:-48}"
DWELL="${3:-7}"
STARTM="${4:-500}"
RECV="com.kovedash.app/.service.NavTestReceiver"

echo "=== glyph probe: codes ${START}..${END}, ${DWELL}s dwell, ${STARTM}m ==="
echo "watch the dash; the road name shows the code number."
echo

for ((n=START; n<=END; n++)); do
  road="IC-${n}"
  adb shell am broadcast -n "$RECV" --ei icon "$n" --es road "$road" --ei startM "$STARTM" >/dev/null 2>&1
  # Pull the exact bytes we put on the wire for this code, for the record.
  sleep 0.6
  tx=$(adb logcat -d -t 60 2>/dev/null | grep "KoveWire" | grep "\"icon\":${n}," | grep '"msg_id":1' | tail -1 | sed 's/.*KoveWire: //')
  printf ">> code %2d  road=%-6s  %s\n" "$n" "$road" "${tx:-(legacy frame not seen in log tail)}"
  if (( n < END )); then sleep "$DWELL"; fi
done

echo
echo "=== done. note which codes drew a glyph vs. blank, and the shape. ==="
