#!/usr/bin/env bash
# PLAN step 25b: warm-up, three runs at a steady rate, then a ramp to find the first rate that breaks a threshold.
# Samples orderflow_outbox_lag_seconds every 2 s and waits for it to drain between runs.
#   scripts/perf_run.sh <output-dir>        (RAMP="100 150 200 300 400 600" scripts/perf_run.sh <dir> to change the ramp steps)
# Uses scripts/load_orders.py (a stand-in for k6). Needs the local stack running (order-service on 8080, Keycloak on 8180).
set -u
OUT=${1:?output directory}
mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd)

( while true; do
    lag=$(curl -s -m 2 localhost:8080/actuator/prometheus | grep '^orderflow_outbox_lag_seconds' | cut -d' ' -f2)
    echo "$(date +%s) ${lag:-NaN}"
    sleep 2
  done ) > "$OUT/lag.txt" &
LAGPID=$!
trap 'kill $LAGPID 2>/dev/null' EXIT

drain() {   # waits until the lag is back to ~0 and prints how long that took after the run ended
  local start=$(date +%s)
  while :; do
    lag=$(curl -s -m 2 localhost:8080/actuator/prometheus | grep '^orderflow_outbox_lag_seconds' | cut -d' ' -f2)
    [ "${lag%.*}" = "0" ] && break
    [ $(( $(date +%s) - start )) -gt 300 ] && break
    sleep 2
  done
  echo $(( $(date +%s) - start ))
}

python "$HERE/load_orders.py" --rate 20 --duration 20 --label warmup > "$OUT/warmup.json"
drain > /dev/null
for i in 1 2 3; do
  echo "run $i start $(date +%s)" >> "$OUT/timeline.txt"
  python "$HERE/load_orders.py" --rate 50 --duration 60 --label "steady 50/s run $i" > "$OUT/steady$i.json"
  echo "run $i end $(date +%s)" >> "$OUT/timeline.txt"
  echo "run $i drain_seconds $(drain)" >> "$OUT/timeline.txt"
done
for rate in ${RAMP:-100 150 200 300}; do
  echo "ramp $rate start $(date +%s)" >> "$OUT/timeline.txt"
  python "$HERE/load_orders.py" --rate $rate --duration 30 --label "ramp $rate/s" > "$OUT/ramp$rate.json"
  echo "ramp $rate end $(date +%s)" >> "$OUT/timeline.txt"
  echo "ramp $rate drain_seconds $(drain)" >> "$OUT/timeline.txt"
done
echo done >> "$OUT/timeline.txt"
