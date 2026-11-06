# Terraform (AWS, ECS Fargate)

**Status: a reviewed draft. `terraform fmt`, `validate`, `plan` and `apply` have NOT been run** (when this was written there was no `terraform` binary, no valid AWS
credentials and no disk for the AWS provider; the binary, Terraform 1.16, has been installed since 2026-11-05, but `validate` has not been run yet and there are still no AWS credentials in use).
Expect to fix small errors on the first `validate`.
Decisions and their costs: `phase2/docs/adr/0006-aws-choices.md`.

State is local while you experiment; it contains generated passwords, so `*.tfstate` and `*.tfvars` are git-ignored.
This is the only step that costs money: set the budget first, and run `terraform destroy` at the end of every session.

## Order of work
1. Billing alerts on, an IAM Identity Center user (or IAM user with MFA), `aws configure sso`. Never use root keys.
2. `terraform init && terraform apply -var budget_email=you@example.com -target=aws_budgets_budget.monthly` (the budget first).
3. Apply the foundations: network, ECR, RDS (`terraform plan` after each group). RDS takes about ten minutes.
4. Push the images (`terraform output push_images_commands`; images must be `linux/amd64`, which a Windows build produces).
5. Create the databases once: `terraform output -raw db_init_command | sh`, then check the task's log group `/orderflow/db-init`.
6. `terraform apply -var image_tag=<commit sha>` brings up Kafka, Keycloak, the services and the ALB.
7. Open `terraform output app_url`, log in as `alice` (dev password in `phase2/infra/keycloak/orderflow-realm.json`) and create an order.
8. `terraform destroy`, then check the console in every region you used: NAT gateways (there are none), load balancers, RDS instances
   and snapshots, ECR images, CloudWatch log groups, Elastic IPs, and Cost Explorer the next day.

## Things to know
- HTTP only. There is no domain, so no ACM certificate; credentials cross the internet in clear text. Demo only.
- The realm's throwaway user passwords are in the repository and the ALB is public: anyone can log in as `alice`. Destroy when done.
- Kafka has no volume: topics are lost when its task restarts.
- During a rolling deploy two order-service tasks briefly overlap and publish the same outbox rows (harmless: idempotent consumer).
