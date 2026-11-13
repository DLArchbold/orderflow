# The five ECS services. Kafka and Keycloak are single containers (ADR 0006); the three application images come from ECR.

locals {
  alb_url    = "http://${aws_lb.main.dns_name}"
  issuer_url = "${local.alb_url}/realms/orderflow" # must equal the token's iss claim, i.e. Keycloak's KC_HOSTNAME
  image      = { for name, repo in aws_ecr_repository.app : name => "${repo.repository_url}:${var.image_tag}" }
  db_secret  = aws_secretsmanager_secret.db.arn
  log_options = { for name, group in aws_cloudwatch_log_group.app : name => {
    "awslogs-group"         = group.name
    "awslogs-region"        = var.region
    "awslogs-stream-prefix" = "ecs"
  } }

  # The realm file from the repository, with the local frontend URL replaced by the ALB URL
  keycloak_realm = replace(replace(file("${path.module}/../keycloak/orderflow-realm.json"),
  "http://localhost:5173/*", "${local.alb_url}/*"), "http://localhost:5173", local.alb_url)
}

# ---- service discovery: kafka.orderflow.local, inventory-service.orderflow.local, keycloak.orderflow.local ----

resource "aws_service_discovery_private_dns_namespace" "internal" {
  name = "${var.project}.local"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "internal" {
  for_each = toset(["kafka", "inventory-service", "keycloak"])
  name     = each.key
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.internal.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

# ---- Kafka: one broker, no volume ----

resource "aws_ecs_task_definition" "kafka" {
  family                   = "${var.project}-kafka"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "kafka"
    image        = "apache/kafka:3.8.0"
    essential    = true
    portMappings = [{ containerPort = 9092, protocol = "tcp" }]
    environment = [
      { name = "KAFKA_NODE_ID", value = "1" },
      { name = "KAFKA_PROCESS_ROLES", value = "broker,controller" },
      { name = "KAFKA_LISTENERS", value = "PLAINTEXT://:9092,CONTROLLER://:9093" },
      # what clients are told to connect to: the Cloud Map name, which resolves inside the VPC
      { name = "KAFKA_ADVERTISED_LISTENERS", value = "PLAINTEXT://kafka.${var.project}.local:9092" },
      { name = "KAFKA_CONTROLLER_LISTENER_NAMES", value = "CONTROLLER" },
      { name = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", value = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" },
      { name = "KAFKA_CONTROLLER_QUORUM_VOTERS", value = "1@localhost:9093" },
      { name = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", value = "1" },
      { name = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", value = "1" },
      { name = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", value = "1" },
    ]
    logConfiguration = { logDriver = "awslogs", options = local.log_options["kafka"] }
  }])
}

resource "aws_ecs_service" "kafka" {
  name            = "kafka"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.kafka.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  # stop, then start: two brokers with separate storage would split the data
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.kafka.id]
    assign_public_ip = true
  }
  service_registries {
    registry_arn = aws_service_discovery_service.internal["kafka"].arn
  }
}

# ---- Keycloak: dev mode with the realm import (not production-grade) ----

resource "aws_ecs_task_definition" "keycloak" {
  family                   = "${var.project}-keycloak"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "keycloak"
    image        = "quay.io/keycloak/keycloak:26.0.7"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    # Fargate cannot mount a file from the repository, so the realm JSON travels in an environment variable and is
    # written to the import directory before Keycloak starts.
    entryPoint = ["/bin/sh", "-c"]
    command    = ["mkdir -p /opt/keycloak/data/import && printf '%s' \"$REALM_JSON\" > /opt/keycloak/data/import/orderflow-realm.json && exec /opt/keycloak/bin/kc.sh start-dev --import-realm"]
    environment = [
      { name = "REALM_JSON", value = local.keycloak_realm },
      { name = "KC_BOOTSTRAP_ADMIN_USERNAME", value = "admin" },
      { name = "KC_HOSTNAME", value = local.alb_url },
      { name = "KC_HTTP_ENABLED", value = "true" },
      { name = "KC_PROXY_HEADERS", value = "xforwarded" }, # it sits behind the ALB
    ]
    secrets          = [{ name = "KC_BOOTSTRAP_ADMIN_PASSWORD", valueFrom = "${aws_secretsmanager_secret.keycloak.arn}:admin_password::" }]
    logConfiguration = { logDriver = "awslogs", options = local.log_options["keycloak"] }
  }])
}

resource "aws_ecs_service" "keycloak" {
  name                               = "keycloak"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.keycloak.arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  health_check_grace_period_seconds  = 180 # Keycloak needs a minute or two to start and import the realm
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.keycloak.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.keycloak.arn
    container_name   = "keycloak"
    container_port   = 8080
  }
  service_registries {
    registry_arn = aws_service_discovery_service.internal["keycloak"].arn
  }
  depends_on = [aws_lb_listener_rule.keycloak]
}

# ---- inventory-service: reachable from order-service only ----

resource "aws_ecs_task_definition" "inventory" {
  family                   = "${var.project}-inventory-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "inventory-service"
    image        = local.image["inventory-service"]
    essential    = true
    portMappings = [{ containerPort = 8081, protocol = "tcp" }]
    environment = [
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.db.address}:5432/inventory" },
      { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "kafka.${var.project}.local:9092" },
      { name = "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", value = "0" }, # no Jaeger here
    ]
    # the password comes from Secrets Manager at start-up; the execution role may read exactly these secrets
    secrets = [
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${local.db_secret}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${local.db_secret}:password::" },
    ]
    logConfiguration = { logDriver = "awslogs", options = local.log_options["inventory-service"] }
  }])
}

resource "aws_ecs_service" "inventory" {
  name                               = "inventory-service"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.inventory.arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.inventory.id]
    assign_public_ip = true
  }
  service_registries {
    registry_arn = aws_service_discovery_service.internal["inventory-service"].arn
  }
  depends_on = [aws_ecs_service.kafka]
}

# ---- order-service: behind the ALB ----

resource "aws_ecs_task_definition" "order" {
  family                   = "${var.project}-order-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024 # 512 took 116 s to start Spring, longer than the grace period below
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "order-service"
    image        = local.image["order-service"]
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.db.address}:5432/orders" },
      { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "kafka.${var.project}.local:9092" },
      { name = "ORDERFLOW_INVENTORY_BASE_URL", value = "http://inventory-service.${var.project}.local:8081" },
      { name = "SPRING_DATA_REDIS_HOST", value = "localhost" }, # no Redis: the stock cache degrades gracefully (step 17c)
      # The token's iss claim is the public ALB URL; the signing keys are fetched from the internal Keycloak name
      { name = "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI", value = local.issuer_url },
      { name = "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI", value = "http://keycloak.${var.project}.local:8080/realms/orderflow/protocol/openid-connect/certs" },
      { name = "ORDERFLOW_CORS_ALLOWED_ORIGIN", value = local.alb_url },
      { name = "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", value = "0" },
    ]
    secrets = [
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${local.db_secret}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${local.db_secret}:password::" },
    ]
    logConfiguration = { logDriver = "awslogs", options = local.log_options["order-service"] }
  }])
}

resource "aws_ecs_service" "order" {
  name            = "order-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.order.arn
  desired_count   = 1
  launch_type     = "FARGATE"
  # Rolling deploy: the new task starts and must be healthy before the old one stops. For a short time TWO order-service
  # tasks run and both publish the same outbox rows; the idempotent consumer makes the duplicate events harmless.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  # Startup took 116 s on 0.5 vCPU and the ALB needs several passing checks after that; 120 s made ECS kill every new task
  health_check_grace_period_seconds = 300
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.order.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.order.arn
    container_name   = "order-service"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener_rule.api, aws_ecs_service.kafka]
}

# ---- frontend (nginx) ----

resource "aws_ecs_task_definition" "frontend" {
  family                   = "${var.project}-frontend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "frontend"
    image        = local.image["frontend"]
    essential    = true
    portMappings = [{ containerPort = 80, protocol = "tcp" }]
    environment = [
      # nginx writes these into /config.js at start-up: the page learns where to log in. The ALB sends /api straight to
      # order-service, so ORDER_SERVICE_URL is only a fallback.
      { name = "OIDC_AUTHORITY", value = local.issuer_url },
      { name = "ORDER_SERVICE_URL", value = local.alb_url },
    ]
    logConfiguration = { logDriver = "awslogs", options = local.log_options["frontend"] }
  }])
}

resource "aws_ecs_service" "frontend" {
  name                               = "frontend"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.frontend.arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.frontend.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 80
  }
  depends_on = [aws_lb_listener.http]
}
