#!/usr/bin/env python3
"""PLAN step 26 follow-up: stop Kafka long enough for the OutboxLagHigh alert to go Pending and then Firing.

  python scripts/drill_lag_alert.py <output-dir> [--outage 240] [--rate 10]

Timeline: load for (30 s + outage + 90 s); Kafka stopped at 30 s and started again after --outage seconds.
Every 5 s the Prometheus alert state (inactive/pending/firing) and the outbox lag are written to <output-dir>/samples.txt.
Works with the services as containers (compose --profile apps) or started with mvn spring-boot:run: it only needs the
published ports, `docker compose` and scripts/load_orders.py. At the end it runs scripts/drill_check.py.
"""
import argparse, json, os, subprocess, sys, threading, time, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMPOSE = ["docker", "compose", "-f", os.path.join(ROOT, "phase2/infra/docker-compose.full.yml")]


def prom(query):
    url = "http://localhost:9090/api/v1/query?" + urllib.parse.urlencode({"query": query})
    with urllib.request.urlopen(url, timeout=5) as r:
        return json.load(r)["data"]["result"]


def sample(out, stop):
    while not stop.is_set():
        try:
            lag = prom("orderflow_outbox_lag_seconds")
            lag = lag[0]["value"][1] if lag else "NA"
            alerts = [a["metric"]["alertstate"] for a in prom('ALERTS{alertname="OutboxLagHigh"}')]
            state = alerts[0] if alerts else "inactive"
        except Exception as e:  # Prometheus or the metric may be briefly unavailable
            lag, state = "NA", f"err:{type(e).__name__}"
        out.write(f"{int(time.time())} lag={lag} OutboxLagHigh={state}\n")
        out.flush()
        stop.wait(5)


def psql(sql):
    r = subprocess.run(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "orderflow", "-d", "orders", "-Atc", sql],
                       capture_output=True, text=True, env={**os.environ, "MSYS_NO_PATHCONV": "1"})
    return r.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--outage", type=int, default=240)
    ap.add_argument("--rate", type=int, default=10)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    timeline = open(os.path.join(a.out, "timeline.txt"), "a")

    def say(msg):
        line = f"{int(time.time())} {msg}"
        print(line, flush=True)
        timeline.write(line + "\n")
        timeline.flush()

    duration = 30 + a.outage + 90
    baseline = psql("select count(*) from orders")
    samples = open(os.path.join(a.out, "samples.txt"), "w")
    stop = threading.Event()
    threading.Thread(target=sample, args=(samples, stop), daemon=True).start()

    say(f"baseline orders={baseline}")
    say(f"load start rate={a.rate} duration={duration}")
    load = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/load_orders.py"), "--rate", str(a.rate),
                             "--duration", str(duration), "--label", f"kafka stopped {a.outage}s"],
                            stdout=open(os.path.join(a.out, "load.json"), "w"))
    time.sleep(30)
    say("STOP kafka")
    subprocess.run(COMPOSE + ["stop", "kafka"], capture_output=True)
    time.sleep(a.outage)
    say("START kafka")
    subprocess.run(COMPOSE + ["start", "kafka"], capture_output=True)
    load.wait()
    say("load end")

    for _ in range(180):  # drain: no PENDING orders left (max 6 minutes)
        pending = psql("select count(*) from orders where status='PENDING'")
        if pending == "0":
            break
        time.sleep(2)
    say(f"drained (pending={pending})")
    time.sleep(10)  # let the alert resolve in the samples
    stop.set()
    samples.close()

    created = json.load(open(os.path.join(a.out, "load.json")))["created_201"]
    say(f"created_201={created}")
    check = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/drill_check.py"), "--created", str(created),
                            "--baseline", baseline], capture_output=True, text=True)
    open(os.path.join(a.out, "check.txt"), "w").write(check.stdout)
    print(check.stdout)


if __name__ == "__main__":
    main()
