# One-shot: run a read-only psql query against the orders DB through the db-init ECS task and print the result.
# Run from phase2/infra/terraform:  .\query-orders.ps1   (optionally -Limit 200)
param([int]$Limit = 50)

$ErrorActionPreference = 'Stop'

$runTask = terraform output -raw db_init_command
$endpoint = terraform output -raw rds_endpoint
$region  = [regex]::Match($runTask, '--region (\S+)').Groups[1].Value
$cluster = [regex]::Match($runTask, '--cluster (\S+)').Groups[1].Value

# SKU and quantity live in order_lines, one row per line item.
$sql = "SELECT o.id, o.customer_id, o.status, l.sku, l.quantity, o.created_at " +
       "FROM orders o JOIN order_lines l ON l.order_id = o.id " +
       "ORDER BY o.created_at DESC, l.line_no LIMIT $Limit;"

$overrides = @{ containerOverrides = @(@{
    name    = 'db-init'
    command = @('sh', '-c', "psql -h $endpoint -U orderflow -d orders -P pager=off -c '$sql'")
}) } | ConvertTo-Json -Depth 5 -Compress

# Write without a BOM (Set-Content -Encoding utf8 adds one in Windows PowerShell 5.1) so the AWS CLI can parse it.
$file = Join-Path $env:TEMP 'orderflow-overrides.json'
[IO.File]::WriteAllText($file, $overrides, (New-Object Text.UTF8Encoding($false)))
$fileUri = 'file://' + ($file -replace '\\', '/')

Write-Host 'Starting task...'
$taskArn = Invoke-Expression "$runTask --overrides $fileUri --query 'tasks[0].taskArn' --output text"
if (-not $taskArn -or $taskArn -eq 'None') { throw 'run-task did not start a task' }
$taskId = $taskArn.Trim().Split('/')[-1]
Write-Host "Task $taskId started; waiting for it to stop..."

aws ecs wait tasks-stopped --region $region --cluster $cluster --tasks $taskArn
$exit = aws ecs describe-tasks --region $region --cluster $cluster --tasks $taskArn --query 'tasks[0].containers[0].exitCode' --output text
Remove-Item $file -ErrorAction SilentlyContinue

# CloudWatch can lag a few seconds behind the task stopping.
Start-Sleep -Seconds 5
aws logs get-log-events --region $region --log-group-name /orderflow/db-init --log-stream-name "ecs/db-init/$taskId" --query 'events[].message' --output text

if ($exit -ne '0') { Write-Error "psql task exited with code $exit"; exit 1 }
