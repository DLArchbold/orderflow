terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # State is LOCAL (one person, one machine). Never commit terraform.tfstate: it contains the generated passwords.
  # Move to an S3 backend with `use_lockfile = true` before sharing; see ADR 0006.
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = var.project }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}
