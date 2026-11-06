# ECR repositories, the RDS instance with a generated password in Secrets Manager, and the AWS Budget.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.project}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_email]
  }
}

resource "aws_ecr_repository" "app" {
  for_each     = toset(["order-service", "inventory-service", "frontend"])
  name         = "${var.project}-${each.key}"
  force_delete = true # so `terraform destroy` works even when images are pushed
}

# ---- database ----

resource "random_password" "db" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.project}/db"
  recovery_window_in_days = 0 # delete immediately on destroy, so the name can be reused the next session
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id     = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({ username = "orderflow", password = random_password.db.result })
}

resource "aws_db_subnet_group" "db" {
  name       = var.project
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "db" {
  identifier              = var.project
  engine                  = "postgres"
  engine_version          = "16"
  instance_class          = "db.t4g.micro"
  allocated_storage       = 20
  storage_type            = "gp3"
  username                = "orderflow"
  password                = random_password.db.result
  db_subnet_group_name    = aws_db_subnet_group.db.name
  vpc_security_group_ids  = [aws_security_group.rds.id]
  publicly_accessible     = false
  multi_az                = false
  backup_retention_period = 0
  skip_final_snapshot     = true # a demo database: nothing to keep after destroy
  deletion_protection     = false
  apply_immediately       = true
}

# ---- Keycloak admin password ----

resource "random_password" "keycloak_admin" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "keycloak" {
  name                    = "${var.project}/keycloak"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "keycloak" {
  secret_id     = aws_secretsmanager_secret.keycloak.id
  secret_string = jsonencode({ admin_password = random_password.keycloak_admin.result })
}
