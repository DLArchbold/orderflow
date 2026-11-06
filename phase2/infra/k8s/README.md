# Kubernetes (light): kind

The goal is "the manifests are valid and both services become Ready on a throwaway cluster", not a production setup.
order-service runs ONE replica: two would both publish the same outbox rows (the known limit from step 13).
Redis, Keycloak and the observability stack are left out: the stock cache degrades gracefully without Redis, and the
readiness check needs no token.

```bash
kind create cluster --name orderflow
# build the images (see docker-compose.full.yml --profile apps) and load them: the pods never pull them
docker compose -f phase2/infra/docker-compose.full.yml --profile apps build
kind load docker-image orderflow-order-service:local orderflow-inventory-service:local --name orderflow
kubectl apply -k phase2/infra/k8s
kubectl wait --for=condition=Available deployment --all --timeout=180s
kubectl port-forward deploy/order-service 18080:8080   # then: curl localhost:18080/actuator/health  ->  {"status":"UP"}  (not 8080: the compose apps profile uses it)
kind delete cluster --name orderflow
```

Check the manifests without a cluster: `kubectl kustomize phase2/infra/k8s`.

Notes
- Verified on 2026-11-05 (kind 0.33, Docker Desktop, Windows 10): all four Deployments Available about 30 s after `kubectl apply` on a warm node, 0 restarts, `/actuator/health` UP
  on both services through `port-forward`, Flyway migrations applied in both databases. Creating a kind cluster the first time took about 3 minutes (node image download).
  Not tested: creating an order inside the cluster (there is no Keycloak here, so no token), and any non-kind cluster.
- The `wait-for-postgres` init containers exist because without them each service started before PostgreSQL, failed in Flyway and was restarted 4 times by kubelet before it settled.
- `startupProbe` gives a service up to 3 minutes to boot before the liveness probe starts; without it the liveness
  probe would kill a Spring Boot app that is still starting.
- `/actuator/health/readiness` exists only because `management.endpoint.health.probes.enabled: true` (step 21a); without
  it the path is a 404 and the pod never becomes Ready.
- The database password is a Kubernetes Secret with a dev-only value. Real deployments read it from a secret store.
