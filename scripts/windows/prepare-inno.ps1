[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$toolsDir = Join-Path $repoRoot 'tools'
$downloadsDir = Join-Path $toolsDir 'downloads'
$installDir = Join-Path $toolsDir 'inno-setup'
$compilerPath = Join-Path $installDir 'ISCC.exe'

if (Test-Path -LiteralPath $compilerPath -PathType Leaf) {
    Write-Host "Inno Setup 已准备完成: $compilerPath"
    exit 0
}

$innoVersion = '7.0.2'
$downloadUri = [Uri]"https://github.com/jrsoftware/issrc/releases/download/is-7_0_2/innosetup-$innoVersion-x64.exe"
$expectedLength = 17020192L
$expectedSha256 = '5AD54CA3DEF786F8F4212552E54CC6D8D61329E2D24A1CFEE0571D42C2684FF1'
$installerPath = Join-Path $downloadsDir "innosetup-$innoVersion-x64.exe"
$partialPath = "$installerPath.download"

New-Item -ItemType Directory -Force -Path $downloadsDir, $installDir | Out-Null

function Assert-OfficialInnoInstaller([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -ne $expectedLength) {
        throw "Inno Setup 安装器大小校验失败: $($file.Length)，预期 $expectedLength"
    }

    $actualSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualSha256 -ne $expectedSha256) {
        throw "Inno Setup 安装器 SHA-256 校验失败: $actualSha256"
    }

    $signature = Get-AuthenticodeSignature -FilePath $Path
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        throw "Inno Setup 安装器 Authenticode 校验失败: $($signature.Status)"
    }
    if ($signature.SignerCertificate.Subject -notmatch 'Pyrsys B\.V\.') {
        throw "Inno Setup 安装器发布者不匹配: $($signature.SignerCertificate.Subject)"
    }

    Write-Host "官方安装器校验通过: SHA-256=${actualSha256}；签名者=$($signature.SignerCertificate.Subject)"
}

$needDownload = -not (Test-Path -LiteralPath $installerPath -PathType Leaf)
if (-not $needDownload) {
    try {
        Assert-OfficialInnoInstaller -Path $installerPath
    } catch {
        Remove-Item -LiteralPath $installerPath -Force
        $needDownload = $true
    }
}

if ($needDownload) {
    Write-Host "正在从官方地址下载 Inno Setup $innoVersion..."
    Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
    try {
        Invoke-WebRequest -Uri $downloadUri -OutFile $partialPath
        Assert-OfficialInnoInstaller -Path $partialPath
        Move-Item -LiteralPath $partialPath -Destination $installerPath -Force
    } finally {
        Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
    }
}

Assert-OfficialInnoInstaller -Path $installerPath

$installerArguments = @(
    '/PORTABLE=1'
    '/VERYSILENT'
    '/SUPPRESSMSGBOXES'
    '/NORESTART'
    '/CURRENTUSER'
    '/NOICONS'
    "/DIR=`"$installDir`""
)

$process = Start-Process `
    -FilePath $installerPath `
    -ArgumentList $installerArguments `
    -Wait `
    -PassThru `
    -WindowStyle Hidden

if ($process.ExitCode -ne 0) {
    throw "Inno Setup portable 安装失败，退出码: $($process.ExitCode)"
}

if (-not (Test-Path -LiteralPath $compilerPath -PathType Leaf)) {
    throw "Inno Setup 安装完成但未找到编译器: $compilerPath"
}

Write-Host "Inno Setup 已准备完成: $compilerPath"
