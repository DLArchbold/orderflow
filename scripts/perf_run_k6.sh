#!/usr/bin/env bash
# PLAN step 25b (k6 version): warm-up, three runs at a steady rate, then a ramp to find the first rate that breaks a threshold.
# Samples orderflow_outbox_lag_seconds every 2 s and waits for it to drain between runs.
#   scripts/perf_run.sh <output-dir>        (RAMP="100 150 200 300 400 600" scripts/perf_run.sh <dir> to change the ramp steps)
# Same as scripts/perf_run.sh but with k6 (phase2/load-tests/k6/create-orders.js) as the load generator. Needs the local stack running (order-service on 8080, Keycloak on 8180).
set -u
OUT=${1:?output directory}
mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd)
K6="$HERE/../phase2/load-tests/k6/create-orders.js"

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

k6 run --quiet -e RATE=20 -e DURATION=20s --summary-export "$OUT/warmup.json" "$K6" > "$OUT/warmup.txt" 2>&1
drain > /dev/null
for i in 1 2 3; do
  echo "run $i start $(date +%s)" >> "$OUT/timeline.txt"
  k6 run --quiet -e RATE=50 -e DURATION=60s --summary-export "$OUT/steady$i.json" "$K6" > "$OUT/steady$i.txt" 2>&1
  echo "run $i end $(date +%s)" >> "$OUT/timeline.txt"
  echo "run $i drain_seconds $(drain)" >> "$OUT/timeline.txt"
done
for rate in ${RAMP:-100 150 200 300}; do
  echo "ramp $rate start $(date +%s)" >> "$OUT/timeline.txt"
  k6 run --quiet -e RATE=$rate -e DURATION=30s --summary-export "$OUT/ramp$rate.json" "$K6" > "$OUT/ramp$rate.txt" 2>&1
  echo "ramp $rate end $(date +%s)" >> "$OUT/timeline.txt"
  echo "ramp $rate drain_seconds $(drain)" >> "$OUT/timeline.txt"
done
docker exec infra-postgres-1 psql -U orderflow -d orders -Atc "select status, count(*) from orders group by 1" >> "$OUT/status_counts.txt" 2>&1
echo done >> "$OUT/timeline.txt"
