# Requires the explicit local-demo Compose topology.
$ErrorActionPreference = "Stop"
$BaseUrl = if ($env:RELAYHUB_URL) { $env:RELAYHUB_URL } else { "http://localhost:8080" }
$admin = @{ 'X-API-Key' = 'rlh_admin_9876543210fedcba' }
$ingest = @{ 'X-API-Key' = 'rlh_live_demo1234567890abcdef'; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$health = Invoke-RestMethod "$BaseUrl/actuator/health"
if ($health.status -ne 'UP') { throw 'Health check failed' }
$subscription = Invoke-RestMethod "$BaseUrl/api/v1/subscriptions" -Method Post -Headers $admin -ContentType application/json -Body '{"targetUrl":"http://mock-receiver:8080/webhook","eventTypes":"demo.completed"}'
try {
    $body = '{"eventType":"demo.completed","payload":{"orderId":1}}'
    $event = Invoke-RestMethod "$BaseUrl/api/v1/events" -Method Post -Headers $ingest -ContentType application/json -Body $body
    $replay = Invoke-WebRequest "$BaseUrl/api/v1/events" -Method Post -Headers $ingest -ContentType application/json -Body $body -UseBasicParsing
    $cached = $replay.Content | ConvertFrom-Json
    if ($replay.StatusCode -ne 201 -or $replay.Headers['Idempotent-Replay'] -ne 'true' -or $cached.eventId -ne $event.eventId) {
        throw 'Idempotency replay did not preserve the accepted event'
    }
    $mismatchStatus = $null
    try {
        Invoke-WebRequest "$BaseUrl/api/v1/events" -Method Post -Headers $ingest -ContentType application/json -Body '{"eventType":"demo.changed","payload":{"orderId":1}}' -UseBasicParsing | Out-Null
    } catch {
        if ($_.Exception.Response) { $mismatchStatus = [int]$_.Exception.Response.StatusCode }
        else { throw }
    }
    if ($mismatchStatus -ne 422) { throw "Expected mismatch rejection 422, received $mismatchStatus" }
    $delivered = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $deliveries = @(Invoke-RestMethod "$BaseUrl/api/v1/deliveries?eventId=$($event.eventId)" -Headers $admin)
        $ours = @($deliveries | Where-Object { $_.subscriptionId -eq $subscription.id })
        if ($ours.Count -eq 1 -and $ours[0].status -eq 'SUCCESS') { $delivered = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $delivered) { throw 'Timed out waiting for successful webhook delivery' }
    $history = @(Invoke-RestMethod "$BaseUrl/api/v1/deliveries/$($ours[0].id)/attempts" -Headers $admin)
    if ($history.Count -lt 1) { throw 'Attempt history is missing' }
    $metrics = (Invoke-WebRequest "$BaseUrl/actuator/prometheus" -Headers $admin -UseBasicParsing).Content
    if ($metrics -notmatch 'relayhub_gateway_requests_total') { throw 'Gateway metrics are missing' }
    Write-Host "PASS: health, role-authenticated setup, idempotency, mismatch rejection, delivery, attempt history, metrics. Event: $($event.eventId)"
} finally {
    # Retain the demo target so its audit history is not deleted by a cascade.
    Write-Host "Demo subscription retained for audit: $($subscription.id)"
}
