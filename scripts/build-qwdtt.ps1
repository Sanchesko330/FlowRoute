param(
    [string]$Abi = "arm64-v8a",
    [switch]$Server
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

if ($Server) {
    $env:GOOS = "linux"
    $env:GOARCH = "amd64"
    $env:CGO_ENABLED = "0"
    $Output = Join-Path $Root "app/src/main/assets/qwdtt-server"
    Push-Location (Join-Path $Root "qwdtt")
    try { go build -trimpath '-ldflags=-s -w' -o $Output ./server } finally { Pop-Location }
    exit $LASTEXITCODE
}

$LocalProperties = Join-Path $Root "local.properties"
$Sdk = $env:ANDROID_SDK_ROOT
if (-not $Sdk -and (Test-Path $LocalProperties)) {
    $Line = Get-Content $LocalProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($Line) { $Sdk = ($Line -replace '^sdk\.dir=', '') -replace '\\\\', '\' }
}
$Ndk = $env:ANDROID_NDK_HOME
if (-not $Ndk -and $Sdk) {
    $Ndk = Get-ChildItem (Join-Path $Sdk "ndk") -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Ndk) { throw "NDK not found. Set ANDROID_NDK_HOME." }

switch ($Abi) {
    "arm64-v8a" { $GoArch = "arm64"; $Clang = "aarch64-linux-android" }
    "armeabi-v7a" { $GoArch = "arm"; $Clang = "armv7a-linux-androideabi" }
    "x86_64" { $GoArch = "amd64"; $Clang = "x86_64-linux-android" }
    default { throw "Unsupported ABI: $Abi" }
}
$Cc = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64/bin/${Clang}26-clang.cmd"
if (-not (Test-Path $Cc)) { throw "Android compiler not found: $Cc" }
$OutputDir = Join-Path $Root "app/src/main/jniLibs/$Abi"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$env:GOOS = "android"
$env:GOARCH = $GoArch
$env:CGO_ENABLED = "1"
$env:CC = $Cc
Push-Location (Join-Path $Root "qwdtt/go_client")
try { go build -trimpath "-ldflags=-s -w -checklinkname=0" -o (Join-Path $OutputDir "libqwdtt.so") . } finally { Pop-Location }
exit $LASTEXITCODE
