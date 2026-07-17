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
$sourceHash = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumHash = ((Get-Content $checksumPath -Raw).Trim() -split "\s+")[0].ToLowerInvariant()

if ($sourceHash -ne $manifest.sha256 -or $sourceHash -ne $checksumHash) {
    throw "Source APK, checksum, and manifest hashes disagree."
}

New-Item -ItemType Directory -Path $Destination -Force | Out-Null

function Publish-AtomicFile([string] $Source, [string] $Name) {
    $temporary = Join-Path $Destination ".$Name.uploading"
    $final = Join-Path $Destination $Name
    Copy-Item $Source $temporary -Force
    $sourceFileHash = (Get-FileHash $Source -Algorithm SHA256).Hash
    $copiedFileHash = (Get-FileHash $temporary -Algorithm SHA256).Hash
    if ($sourceFileHash -ne $copiedFileHash) {
        Remove-Item $temporary -Force -ErrorAction SilentlyContinue
        throw "Copied hash mismatch for $Name."
    }
    Move-Item $temporary $final -Force
}

Publish-AtomicFile $apkPath $apkName
Publish-AtomicFile $checksumPath $checksumName
Publish-AtomicFile $manifestPath "manifest.json"

Write-Host "Published KelliKanvas update $($manifest.versionCode) to $Destination"
