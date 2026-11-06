#!/usr/bin/env bash
# Repeats the k6 steps that stalled (300/s and 600/s) and samples order-service and Postgres every second to find the cause.
#   scripts/stall_hunt.sh <output-dir>      (RATES="300 600 300 600 300 600" to change the sequence)
#   K6_MODE=docker scripts/stall_hunt.sh <dir>   runs k6 in a container on the compose network (grafana/k6:2.3.0, no Windows port forward)
set -u
OUT=${1:?output directory}; mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd); K6="$HERE/../phase2/load-tests/k6/create-orders.js"
PSQL="docker exec infra-postgres-1 psql -U orderflow -d orders -Atc"
( echo "ts hikari_active hikari_pending hikari_timeouts http_active gc_pause_sum_s gc_count proc_cpu pg_active pg_waiting ckpt_timed ckpt_req" > "$OUT/samples.txt"
  while true; do
    m=$(curl -s -m 2 localhost:8080/actuator/prometheus)
    g() { echo "$m" | grep -E "$1" | awk '{s+=$NF} END{print s+0}'; }
    pg=$($PSQL "select count(*) filter (where state='active'), count(*) filter (where wait_event_type='Lock' or wait_event_type='LWLock'), (select checkpoints_timed from pg_stat_bgwriter), (select checkpoints_req from pg_stat_bgwriter) from pg_stat_activity where datname='orders'" 2>/dev/null | tr '|' ' ')
    echo "$(date +%s) $(g '^hikaricp_connections_active') $(g '^hikaricp_connections_pending') $(g '^hikaricp_connections_timeout_total') $(g '^http_server_requests_active_seconds_gcount') $(g '^jvm_gc_pause_seconds_sum') $(g '^jvm_gc_pause_seconds_count') $(g '^process_cpu_usage') $pg" >> "$OUT/samples.txt"
    sleep 1
  done ) &
SPID=$!; trap 'kill $SPID 2>/dev/null' EXIT
drain() { local s=$(date +%s); while :; do l=$(curl -s -m 2 localhost:8080/actuator/prometheus | grep '^orderflow_outbox_lag_seconds' | cut -d' ' -f2); [ "${l%.*}" = "0" ] && break; [ $(( $(date +%s)-s )) -gt 300 ] && break; sleep 2; done; echo $(( $(date +%s)-s )); }
n=0
for rate in ${RATES:-300 600 300 600 300 600}; do
  n=$((n+1)); echo "run$n rate $rate start $(date +%s)" >> "$OUT/timeline.txt"
  if [ "${K6_MODE:-host}" = docker ]; then
    MSYS_NO_PATHCONV=1 docker run --rm --network infra_default -v "$(cd "$HERE/.." && pwd -W 2>/dev/null || pwd):/work"       -e BASE_URL=http://order-service:8080 -e KEYCLOAK_URL=http://keycloak:8080 -e RATE=$rate -e DURATION=30s       grafana/k6:2.3.0 run --quiet --summary-export "/work/${OUT#$(cd "$HERE/.." && pwd)/}/run$n-$rate.json" /work/phase2/load-tests/k6/create-orders.js > "$OUT/run$n-$rate.txt" 2>&1
  else
    k6 run --quiet -e RATE=$rate -e DURATION=30s --summary-export "$OUT/run$n-$rate.json" "$K6" > "$OUT/run$n-$rate.txt" 2>&1
  fi
  echo "run$n rate $rate end $(date +%s)" >> "$OUT/timeline.txt"
  echo "run$n drain_seconds $(drain)" >> "$OUT/timeline.txt"
  sleep 20
done
echo done >> "$OUT/timeline.txt"
