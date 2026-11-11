# One-shot: run a read-only psql query against the inventory DB through the db-init ECS task and print the result.
# Run from phase2/infra/terraform:  .\query-inventory.ps1
$ErrorActionPreference = 'Stop'

$runTask = terraform output -raw db_init_command
$endpoint = terraform output -raw rds_endpoint
$region  = [regex]::Match($runTask, '--region (\S+)').Groups[1].Value
$cluster = [regex]::Match($runTask, '--cluster (\S+)').Groups[1].Value

# stock holds one row per SKU; reservations holds the per-order holds that make up "reserved".
$sql = "SELECT s.sku, s.available, s.reserved, s.available + s.reserved AS total, " +
       "(SELECT count(*) FROM reservations r WHERE r.sku = s.sku) AS reservations " +
       "FROM stock s ORDER BY s.sku;"

$overrides = @{ containerOverrides = @(@{
    name    = 'db-init'
    command = @('sh', '-c', "psql -h $endpoint -U orderflow -d inventory -P pager=off -c '$sql'")
}) } | ConvertTo-Json -Depth 5 -Compress

# Write without a BOM (Set-Content -Encoding utf8 adds one in Windows PowerShell 5.1) so the AWS CLI can parse it.
$file = Join-Path $env:TEMP 'orderflow-inventory-overrides.json'
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
