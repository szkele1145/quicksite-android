# Build QuickSite assets (Windows): slim bootstrap + pre-installed nginx + frpc
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-assets.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dl = if ($env:QUICKSITE_DL) { $env:QUICKSITE_DL } else { 'F:\vibe coding工程\_android\dl' }
$work = Join-Path $root 'assets-work'
$out = Join-Path $root 'app\src\main\assets'

New-Item -ItemType Directory -Force -Path $work, $out | Out-Null

# 1. copy bootstrap
$bs = Join-Path $work 'bs'
if (Test-Path $bs) { Remove-Item $bs -Recurse -Force }
Copy-Item (Join-Path $dl 'bs') $bs -Recurse

# 2. slim down
$prunes = @(
  'share\misc\magic.mgc',
  'share\doc',
  'share\info',
  'share\man',
  'libexec\installed-tests',
  'libexec\termux-am',
  'libexec\termux\command-not-found',
  'var\log',
  'var\cache\apt',
  'etc\apt'
)
foreach ($p in $prunes) {
  $t = Join-Path $bs $p
  if (Test-Path $t) { Remove-Item $t -Recurse -Force }
}
foreach ($l in @('libapt-pkg.so','libapt-private.so','libgnutls.so','libunbound.so','libp11-kit.so','libunistring.so','pkcs11')) {
  $t = Join-Path $bs "lib\$l"
  if (Test-Path $t) { Remove-Item $t -Recurse -Force }
}
$sz = (Get-ChildItem $bs -Recurse -File | Measure-Object Length -Sum).Sum
Write-Output ("bootstrap after slim: {0:N1} MB" -f ($sz/1MB))

# 3. nginx deb
& python (Join-Path $PSScriptRoot 'deb_extract.py') (Join-Path $dl 'nginx_1.31.8_aarch64.deb') $bs
if ($LASTEXITCODE -ne 0) { throw 'nginx deb extraction failed' }

# 4. frpc
& python (Join-Path $PSScriptRoot 'tar_extract.py') (Join-Path $dl 'frp-android-arm64.tar.gz') (Join-Path $out 'frpc.bin') frpc
if ($LASTEXITCODE -ne 0) { throw 'frpc extraction failed' }

# 5. pack
$bootZip = Join-Path $out 'bootstrap.zip'
if (Test-Path $bootZip) { Remove-Item $bootZip -Force }
& python (Join-Path $PSScriptRoot 'zip_tree.py') $bs $bootZip
if ($LASTEXITCODE -ne 0) { throw 'bootstrap packing failed' }

Write-Output '== assets build done =='
Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize