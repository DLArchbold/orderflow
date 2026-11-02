# Phase 1 shortcuts (needs Docker for Postgres, JDK 21, Maven, Node).
.PHONY: db db-stop backend frontend
db:            ## start Postgres on localhost:5432
	docker compose -f infra/docker-compose.yml up -d
db-stop:
	docker compose -f infra/docker-compose.yml down
backend:       ## Swagger UI: http://localhost:8080/swagger-ui.html
	cd services/order-service && mvn spring-boot:run
frontend:      ## UI: http://localhost:5173
	cd frontend && npm install && npm run dev
