# Generates a random 32-byte PEPPER and stores it as a Cloudflare Worker secret.
# The value is piped straight to wrangler and never printed.
# Run ONCE per deployment: changing PEPPER later breaks every existing password.
#   powershell -ExecutionPolicy Bypass -File scripts\set-pepper.ps1
Set-Location (Join-Path $PSScriptRoot '..')
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes) | npx.cmd wrangler secret put PEPPER
