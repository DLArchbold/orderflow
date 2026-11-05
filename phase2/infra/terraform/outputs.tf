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
