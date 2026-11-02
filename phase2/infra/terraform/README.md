# Terraform

State is local while you experiment; move it to S3 + DynamoDB locking before sharing.
Run `terraform destroy` when you finish a session to stop costs, and keep your plan/apply output as evidence.
Never commit `*.tfstate` or secrets.
