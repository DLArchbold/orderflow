# Terraform (AWS, ECS Fargate)

**Status: passes `terraform fmt` and `terraform validate` (Terraform 1.16.6, 2026-11-06, `init -backend=false`). Applied in part by hand (2026-11-09/10): the budget, network, ECR, RDS and the one-off `db-init` task (exit code 0, both databases created). The full apply, the services behind the ALB, the deploy under load and a clean `destroy` check are not recorded here.**
`validate` checks syntax and argument names, not whether the resources will create; expect to fix errors on the first `plan`.
Decisions and their costs: `phase2/docs/adr/0006-aws-choices.md`.

State is local while you experiment; it contains generated passwords, so `*.tfstate` and `*.tfvars` are git-ignored.
This is the only step that costs money: set the budget first, and run `terraform destroy` at the end of every session.

## Order of work
1. Billing alerts on, an IAM Identity Center user (or IAM user with MFA), `aws configure sso`. Never use root keys.
2. `terraform init && terraform apply -var budget_email=you@example.com "-target=aws_budgets_budget.monthly"` (the budget first; the quotes matter in PowerShell, and the SSO role needs `AdministratorAccess`, since `SystemAdministrator` lacks `budgets:ModifyBudget`).
3. Apply the foundations: network, ECR, RDS (`terraform plan` after each group). RDS takes about ten minutes.
4. Push the images (`terraform output push_images_commands`; images must be `linux/amd64`, which a Windows build produces).
5. Create the databases once, then check the task's log group `/orderflow/db-init` for two `CREATE DATABASE` lines (see "Running these on Windows PowerShell" for the PowerShell form):
   `terraform output -raw db_init_command | sh` (bash), or `Invoke-Expression (terraform output -raw db_init_command)` (PowerShell).
   This needs the full state: the ECS cluster, the `db-init` task definition and `aws_iam_role_policy.execution_secrets`. If the output is `null` or the task stops with an `AccessDeniedException`, see "Troubleshooting".
6. `terraform apply -var image_tag=<commit sha>` brings up Kafka, Keycloak, the services and the ALB.
7. Open `terraform output app_url`, log in as `alice` (dev password in `phase2/infra/keycloak/orderflow-realm.json`) and create an order.
8. `terraform destroy`, then check the console in every region you used: NAT gateways (there are none), load balancers, RDS instances
   and snapshots, ECR images, CloudWatch log groups, Elastic IPs, and Cost Explorer the next day.

## Running these on Windows PowerShell
The commands above are written for bash. In PowerShell 5.1:
- There is no `sh`: `terraform output -raw db_init_command | sh` fails with "'sh' is not recognized". Use `Invoke-Expression (terraform output -raw db_init_command)`, or run it in Git Bash.
- Quote every `-target`: `"-target=aws_budgets_budget.monthly"`. Unquoted, PowerShell splits the flag at the dot.
- A trailing `\` does not continue a line (use a backtick, or one long line). `aws ecs describe-tasks ... \ --query ...` fails with "taskId length should be one of [32,36]" because the backslash reaches `aws` as part of the task ID.
- Use double quotes around `--query` values.
- `aws logs tail ... --follow` never exits; press Ctrl+C. Without `--follow`, add `--since 30m`.

## Troubleshooting
Every problem met so far while creating the databases came from a half-built state left by `-target` applies and a destroy, or from bash habits in PowerShell.
- **`terraform output -raw db_init_command` says the value is `null`.** The output needs `aws_ecs_cluster.main`, `aws_ecs_task_definition.db_init`, a subnet and a security group. If one is missing from `terraform state list`, the output is null. Create what is missing, then run `terraform apply -refresh-only` (a targeted apply does not recompute outputs whose dependencies were not all targeted) or a plain `terraform apply`.
- **The `db-init` task stops with `ResourceInitializationError ... AccessDeniedException ... secretsmanager:GetSecretValue`.** `aws_iam_role_policy.execution_secrets` was never created. Nothing references it, so a targeted apply of the task definition skips it. Apply it (and `aws_iam_role_policy_attachment.execution_managed`), wait 10 to 30 seconds for IAM, then start a new task. A stopped task is never retried.
- **Read the real cause with** `aws ecs describe-tasks --cluster orderflow --tasks <id> --region us-east-1 --query "tasks[0].{status:lastStatus,exit:containers[0].exitCode,reason:stoppedReason}"`. `exit: 0` with "Essential container in task exited" is success for a one-off task; each run has its own task ID, so an old failed task keeps its old error.
- **Prefer a plain `terraform apply` once the budget exists.** `-target` leaves out resources nothing depends on (IAM policies, for one) and leaves outputs stale. Use it deliberately, then finish with a full apply.
- **The ECS services and ALB** that the full apply creates fail to start until the images are pushed and the databases exist, and they cost money while they run.

## Things to know
- HTTP only. There is no domain, so no ACM certificate; credentials cross the internet in clear text. Demo only.
- The realm's throwaway user passwords are in the repository and the ALB is public: anyone can log in as `alice`. Destroy when done.
- Kafka has no volume: topics are lost when its task restarts.
- During a rolling deploy two order-service tasks briefly overlap and publish the same outbox rows (harmless: idempotent consumer).
