[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BundlePath,
    [string] $Destination = "\\DarklingNAS\Public\KelliKanvas"
)

$ErrorActionPreference = "Stop"
$bundle = (Resolve-Path $BundlePath).Path
$manifestPath = Join-Path $bundle "manifest.json"
$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json

if ($manifest.schema -ne 1 -or $manifest.packageName -ne "com.jedon.kellikanvas") {
    throw "Invalid KelliKanvas update manifest."
}

$apkName = [IO.Path]::GetFileName(([Uri] $manifest.apkUrl).AbsolutePath)
$checksumName = [IO.Path]::GetFileName(([Uri] $manifest.checksumUrl).AbsolutePath)
$apkPath = Join-Path $bundle $apkName
$checksumPath = Join-Path $bundle $checksumName
$signaturePath = Join-Path $bundle "manifest.json.sig"
if (!(Test-Path $signaturePath) -or (Get-Item $signaturePath).Length -le 0 -or (Get-Item $signaturePath).Length -gt 1024) {
    throw "Missing or invalid authenticated metadata signature."
}
$sourceHash = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumHash = ((Get-Content $checksumPath -Raw).Trim() -split "\s+")[0].ToLowerInvariant()

if ($sourceHash -ne $manifest.sha256 -or $sourceHash -ne $checksumHash) {
    throw "Source APK, checksum, and manifest hashes disagree."
}

New-Item -ItemType Directory -Path $Destination -Force | Out-Null

function Stage-File([string] $Source, [string] $Name) {
    $temporary = Join-Path $Destination ".$Name.uploading"
    Copy-Item $Source $temporary -Force
    $sourceFileHash = (Get-FileHash $Source -Algorithm SHA256).Hash
    $copiedFileHash = (Get-FileHash $temporary -Algorithm SHA256).Hash
    if ($sourceFileHash -ne $copiedFileHash) {
        Remove-Item $temporary -Force -ErrorAction SilentlyContinue
        throw "Copied hash mismatch for $Name."
    }
    return $temporary
}

$staged = @()
try {
    $staged += @{ Temp = (Stage-File $apkPath $apkName); Name = $apkName }
    $staged += @{ Temp = (Stage-File $checksumPath $checksumName); Name = $checksumName }
    $staged += @{ Temp = (Stage-File $signaturePath "manifest.json.sig"); Name = "manifest.json.sig" }
    $staged += @{ Temp = (Stage-File $manifestPath "manifest.json"); Name = "manifest.json" }
    foreach ($file in $staged | Where-Object { $_.Name -ne "manifest.json" }) {
        Move-Item $file.Temp (Join-Path $Destination $file.Name) -Force
    }
    $manifestStage = $staged | Where-Object { $_.Name -eq "manifest.json" }
    Move-Item $manifestStage.Temp (Join-Path $Destination "manifest.json") -Force
} finally {
    foreach ($file in $staged) {
        Remove-Item $file.Temp -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Published KelliKanvas update $($manifest.versionCode) to $Destination"
