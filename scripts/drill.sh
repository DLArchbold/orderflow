#!/usr/bin/env bash
# PLAN step 26b: break one piece while a steady load runs, wait for the pipeline to drain, then run the checks.
#   scripts/drill.sh kafka|inventory|order <output-dir>
# kafka     docker compose stop kafka for 60 s, then start (stop/start, not down, so the data stays)
# inventory kill inventory-service for 60 s, then start it again
# order     kill order-service during the load (taskkill /F), start it again 20 s later
# Needs the local stack and both services started with mvn spring-boot:run (as in the README quick-start).
set -u
export MSYS_NO_PATHCONV=1
WHAT=${1:?kafka|inventory|order}; OUT=${2:?output dir}; mkdir -p "$OUT"
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
C="docker compose -f phase2/infra/docker-compose.full.yml"
RATE=20; DURATION=180
say() { echo "$(date +%s) $*" | tee -a "$OUT/timeline.txt"; }

kill_port() { pid=$(netstat -ano | grep ":$1 .*LISTENING" | head -1 | awk '{print $5}'); [ -n "$pid" ] && taskkill /F /PID "$pid" /T > /dev/null; }
wait_up() { for i in $(seq 1 60); do curl -s -m 2 "localhost:$1/actuator/health" | grep -q UP && return; sleep 3; done; }
start_inventory() { (cd services/inventory-service && nohup mvn -q spring-boot:run -Dspring-boot.run.arguments=--orderflow.admin.enabled=true > "$OUT/inventory.log" 2>&1 &); }
start_order() { (cd services/order-service && nohup mvn -q spring-boot:run > "$OUT/order.log" 2>&1 &); }

( while true; do
    lag=$(curl -s -m 2 localhost:8080/actuator/prometheus | grep '^orderflow_outbox_lag_seconds' | cut -d' ' -f2)
    pend=$($C exec -T postgres psql -U orderflow -d orders -Atc "select count(*) from orders where status='PENDING'" 2>/dev/null)
    echo "$(date +%s) lag=${lag:-NA} pending=${pend:-NA}"
    sleep 2
  done ) > "$OUT/samples.txt" &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT

BASELINE=$($C exec -T postgres psql -U orderflow -d orders -Atc "select count(*) from orders")
say "baseline orders=$BASELINE"
say "load start rate=$RATE duration=$DURATION"
python scripts/load_orders.py --rate $RATE --duration $DURATION --label "drill $WHAT" > "$OUT/load.json" &
LOAD=$!
sleep 30
case "$WHAT" in
  kafka)     say "STOP kafka";  $C stop kafka > /dev/null; sleep 60; say "START kafka"; $C start kafka > /dev/null ;;
  inventory) say "KILL inventory-service"; kill_port 8081; sleep 60; say "START inventory-service"; start_inventory ;;
  order)     say "KILL order-service"; kill_port 8080; sleep 20; say "START order-service"; start_order ;;
esac
wait $LOAD
say "load end"

# wait for the pipeline to drain: no PENDING orders left (max 6 minutes)
for i in $(seq 1 180); do
  pend=$($C exec -T postgres psql -U orderflow -d orders -Atc "select count(*) from orders where status='PENDING'")
  [ "$pend" = "0" ] && break
  sleep 2
done
say "drained (pending=$pend)"
sleep 5

CREATED=$(python -c "import json;print(json.load(open('$OUT/load.json'))['created_201'])")
say "created_201=$CREATED"
python scripts/drill_check.py --created "$CREATED" --baseline "$BASELINE" | tee "$OUT/check.txt"
