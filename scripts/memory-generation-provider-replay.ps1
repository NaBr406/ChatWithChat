param(
    [Parameter(Mandatory = $true)][string]$ProviderFile,
    [Parameter(Mandatory = $true)][string]$Model,
    [string]$RequestDirectory = 'app/build/reports/memory/generation-requests',
    [string]$ResponseDirectory = 'app/build/reports/memory/generation-responses'
)

$ErrorActionPreference = 'Stop'
$settings = @(Get-Content -LiteralPath $ProviderFile -Encoding UTF8 |
    Where-Object { $_.Trim() } |
    ForEach-Object { ($_ -split '\s+', 2)[-1].Trim() })
if ($settings.Count -ne 3) { throw 'Provider file must contain model, URL, and key lines.' }
$endpoint = "$($settings[1].TrimEnd('/'))/chat/completions"
$headers = @{ Authorization = "Bearer $($settings[2])" }
New-Item -ItemType Directory -Path $ResponseDirectory -Force | Out-Null
$rows = @()
foreach ($caseId in @('addressing', 'transient', 'project', 'correction')) {
    $requestFile = Join-Path $RequestDirectory "$caseId.json"
    $request = Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $request.model = $Model
    $body = $request | ConvertTo-Json -Depth 30 -Compress
    $response = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120
    if ($response.choices[0].finish_reason -ne 'stop') { throw "Incomplete response: $caseId" }
    $content = [string]$response.choices[0].message.content
    if ([string]::IsNullOrWhiteSpace($content)) { throw "Empty response: $caseId" }
    $content | Set-Content -LiteralPath (Join-Path $ResponseDirectory "$caseId.json") -Encoding UTF8
    $rows += [ordered]@{
        case = $caseId
        requestHash = (Get-FileHash -LiteralPath $requestFile -Algorithm SHA256).Hash
        responseHash = (Get-FileHash -LiteralPath (Join-Path $ResponseDirectory "$caseId.json") -Algorithm SHA256).Hash
        promptTokens = $response.usage.prompt_tokens
        completionTokens = $response.usage.completion_tokens
    }
    Write-Output "generation-provider|$caseId|received=true"
}
[ordered]@{
    model = $Model
    providerHost = ([uri]$settings[1]).Host
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    rows = $rows
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $ResponseDirectory 'manifest.json') -Encoding UTF8
