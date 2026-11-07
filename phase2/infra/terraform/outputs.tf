output "ecr_repositories" {
  value = { for name, repo in aws_ecr_repository.app : name => repo.repository_url }
}

output "rds_endpoint" {
  value = aws_db_instance.db.address
}

# Run once after the first apply, before the services start; it creates the two databases inside RDS.
output "db_init_command" {
  value = join(" ", [
    "aws ecs run-task --region ${var.region} --cluster ${aws_ecs_cluster.main.name} --launch-type FARGATE",
    "--task-definition ${aws_ecs_task_definition.db_init.family}",
    "--network-configuration 'awsvpcConfiguration={subnets=[${aws_subnet.public[0].id}],securityGroups=[${aws_security_group.dbinit.id}],assignPublicIp=ENABLED}'",
  ])
}

output "app_url" {
  description = "Public URL of the whole system (HTTP only)"
  value       = "http://${aws_lb.main.dns_name}"
}

output "push_images_commands" {
  description = "Log in to ECR; then docker tag/push each image with the tag you pass as image_tag"
  value       = "aws ecr get-login-password --region ${var.region} | docker login --username AWS --password-stdin ${split("/", aws_ecr_repository.app["order-service"].repository_url)[0]}"
}
