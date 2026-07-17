[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BundlePath,
    [string] $Destination = "\\DarklingNAS\Public\KelliKanvas",
    [Parameter(Mandatory = $true)]
    [string] $MetadataPublicKeyFile,
    [string] $MetadataKeyId = "release-v1"
)

$ErrorActionPreference = "Stop"
$bundle = (Resolve-Path $BundlePath).Path
$controlPath = Join-Path $bundle "update-envelope.json"
$verifier = Join-Path $PSScriptRoot "verify_update_envelope.py"
$payloadJson = & python $verifier --envelope $controlPath --public-key $MetadataPublicKeyFile --key-id $MetadataKeyId
if ($LASTEXITCODE -ne 0) {
    throw "Authenticated update envelope verification failed."
}
$manifest = $payloadJson | ConvertFrom-Json

if ($manifest.schema -ne 1 -or $manifest.packageName -ne "com.jedon.kellikanvas") {
    throw "Invalid KelliKanvas update manifest."
}

$apkName = [IO.Path]::GetFileName(([Uri] $manifest.apkUrl).AbsolutePath)
$checksumName = [IO.Path]::GetFileName(([Uri] $manifest.checksumUrl).AbsolutePath)
$apkPath = Join-Path $bundle $apkName
$checksumPath = Join-Path $bundle $checksumName
$sourceHash = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumHash = ((Get-Content $checksumPath -Raw).Trim() -split "\s+")[0].ToLowerInvariant()

if ($sourceHash -ne $manifest.sha256 -or $sourceHash -ne $checksumHash) {
    throw "Source APK, checksum, and manifest hashes disagree."
}

New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$deployedControl = Join-Path $Destination "update-envelope.json"
if (Test-Path $deployedControl) {
    $deployedPayloadJson = & python $verifier --envelope $deployedControl --public-key $MetadataPublicKeyFile --key-id $MetadataKeyId
    if ($LASTEXITCODE -ne 0) {
        throw "Existing deployed control envelope is invalid; refusing replacement."
    }
    $deployed = $deployedPayloadJson | ConvertFrom-Json
    $sameControl = (Get-FileHash $controlPath -Algorithm SHA256).Hash -eq (Get-FileHash $deployedControl -Algorithm SHA256).Hash
    if (!$sameControl -and (
        [long] $manifest.sequence -le [long] $deployed.sequence -or
        [long] $manifest.versionCode -lt [long] $deployed.versionCode
    )) {
        throw "Release sequence/version is not monotonic."
    }
}

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
    $staged += @{ Temp = (Stage-File $controlPath "update-envelope.json"); Name = "update-envelope.json" }
    foreach ($file in $staged | Where-Object { $_.Name -ne "update-envelope.json" }) {
        Move-Item $file.Temp (Join-Path $Destination $file.Name) -Force
    }
    $controlStage = $staged | Where-Object { $_.Name -eq "update-envelope.json" }
    Move-Item $controlStage.Temp $deployedControl -Force
} finally {
    foreach ($file in $staged) {
        Remove-Item $file.Temp -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Published KelliKanvas update $($manifest.versionCode) to $Destination"
