# ECS cluster, log groups, IAM roles, and the one-off task that creates the two databases inside RDS
# (Terraform cannot create databases inside RDS, and RDS is only reachable from inside the VPC).

resource "aws_ecs_cluster" "main" {
  name = var.project
}

locals {
  log_groups = ["order-service", "inventory-service", "frontend", "kafka", "keycloak", "db-init"]
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = toset(local.log_groups)
  name              = "/${var.project}/${each.key}"
  retention_in_days = 7
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# The execution role pulls images, writes logs and reads the secrets that are injected into the containers.
resource "aws_iam_role" "execution" {
  name               = "${var.project}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [aws_secretsmanager_secret.db.arn, aws_secretsmanager_secret.keycloak.arn]
    }]
  })
}

# The task role is what the application code itself may do in AWS: nothing.
resource "aws_iam_role" "task" {
  name               = "${var.project}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# ---- one-off task: CREATE DATABASE orders / inventory ----
# Run it with the command printed by `terraform output db_init_command`.
resource "aws_ecs_task_definition" "db_init" {
  family                   = "${var.project}-db-init"
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
    name      = "db-init"
    image     = "postgres:16"
    essential = true
    command = ["sh", "-c",
    "psql -h ${aws_db_instance.db.address} -U orderflow -d postgres -c 'CREATE DATABASE orders' -c 'CREATE DATABASE inventory'"]
    secrets = [{ name = "PGPASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app["db-init"].name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])
}
