terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.region
}

# PLAN step 17: build these up one at a time, running `terraform plan` after each.
# - networking (VPC, subnets, security groups)
# - ECR repositories
# - RDS PostgreSQL
# - Kafka (MSK Serverless OR a single container, your choice; note cost)
# - ECS cluster, task definitions, services (rolling deploy), ALB
# - Secrets Manager entries
