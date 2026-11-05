variable "region" {
  type    = string
  default = "us-east-1"
}

variable "project" {
  type    = string
  default = "orderflow"
}

variable "image_tag" {
  description = "Tag of the three application images in ECR (use the commit SHA, so a deploy or rollback names one build)"
  type        = string
  default     = "latest"
}

variable "budget_email" {
  description = "Address that gets the AWS Budget alerts. Required: set the budget before creating anything that costs money."
  type        = string
}

variable "budget_usd" {
  type    = number
  default = 10
}
