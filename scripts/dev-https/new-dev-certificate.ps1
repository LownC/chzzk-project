<#
.SYNOPSIS
  Creates a local development CA and an HTTPS server certificate for the chzzk dev server.

.DESCRIPTION
  Phone browsers allow the camera (getUserMedia) only on HTTPS. The phone reaches the PC by its LAN IP,
  so the server certificate must contain that IP in Subject Alternative Name, and the phone must trust
  the issuing CA. This script uses only the JDK keytool.

  Output (outside the repository by default, never commit these files):
    chzzk-dev-ca.crt  install on the Android phone as a "CA certificate"
    chzzk-dev-ca.p12  CA private key (keep it to re-issue the server certificate)
    chzzk-dev.p12     server key store for Spring Boot profile "https" (alias chzzk-dev)

.EXAMPLE
  .\scripts\dev-https\new-dev-certificate.ps1
  .\scripts\dev-https\new-dev-certificate.ps1 -IpAddress 192.168.0.10 -OutputDirectory D:/chzzk/certs
#>
param(
	[string]$OutputDirectory = (Join-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) 'chzzk_certs'),
	[string[]]$IpAddress,
	[SecureString]$KeyStorePassword,
	[int]$ValidityDays = 397,
	[switch]$Force
)

$ErrorActionPreference = 'Stop'

$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }

function Invoke-Keytool([string[]]$Arguments) {
	$previous = $ErrorActionPreference
	$ErrorActionPreference = 'Continue'
	$output = & $keytool @Arguments 2>&1
	$exitCode = $LASTEXITCODE
	$ErrorActionPreference = $previous
	if ($exitCode -ne 0) {
		throw "keytool failed ($exitCode): $($output -join ' ')"
	}
}

if (-not $IpAddress) {
	$IpAddress = Get-NetIPConfiguration |
		Where-Object { $_.IPv4DefaultGateway -ne $null } |
		ForEach-Object { $_.IPv4Address.IPAddress }
}
if (-not $IpAddress) {
	throw 'Could not detect the LAN IP address. Pass it with -IpAddress.'
}

if (-not $KeyStorePassword) {
	$KeyStorePassword = Read-Host -AsSecureString 'Key store password (6+ characters)'
}
$password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
	[Runtime.InteropServices.Marshal]::SecureStringToBSTR($KeyStorePassword))
if ($password.Length -lt 6) {
	throw 'The key store password must be at least 6 characters.'
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$caStore = Join-Path $OutputDirectory 'chzzk-dev-ca.p12'
$caCert = Join-Path $OutputDirectory 'chzzk-dev-ca.crt'
$serverStore = Join-Path $OutputDirectory 'chzzk-dev.p12'
$request = Join-Path $OutputDirectory 'chzzk-dev.csr'
$serverCert = Join-Path $OutputDirectory 'chzzk-dev.crt'

foreach ($existing in $caStore, $caCert, $serverStore) {
	if ((Test-Path $existing) -and -not $Force) {
		throw "$existing already exists. Use -Force to replace the certificates."
	}
}
Remove-Item $caStore, $caCert, $serverStore, $request, $serverCert -ErrorAction SilentlyContinue

$san = (@($IpAddress | ForEach-Object { "IP:$_" }) + @('IP:127.0.0.1', 'DNS:localhost')) -join ','

# 1. Local CA (CA:true, may sign certificates)
Invoke-Keytool @('-genkeypair', '-alias', 'chzzk-dev-ca', '-keyalg', 'RSA', '-keysize', '2048',
	'-dname', 'CN=chzzk local development CA', '-validity', "$ValidityDays",
	'-ext', 'BC:critical=ca:true', '-ext', 'KU:critical=keyCertSign,cRLSign',
	'-keystore', $caStore, '-storetype', 'PKCS12', '-storepass', $password)
Invoke-Keytool @('-exportcert', '-alias', 'chzzk-dev-ca', '-keystore', $caStore, '-storepass', $password,
	'-rfc', '-file', $caCert)

# 2. Server key pair and certificate signed by the CA, with the LAN IP in SAN
Invoke-Keytool @('-genkeypair', '-alias', 'chzzk-dev', '-keyalg', 'RSA', '-keysize', '2048',
	'-dname', 'CN=chzzk development server', '-validity', "$ValidityDays",
	'-keystore', $serverStore, '-storetype', 'PKCS12', '-storepass', $password)
Invoke-Keytool @('-certreq', '-alias', 'chzzk-dev', '-keystore', $serverStore, '-storepass', $password,
	'-file', $request)
Invoke-Keytool @('-gencert', '-alias', 'chzzk-dev-ca', '-keystore', $caStore, '-storepass', $password,
	'-infile', $request, '-outfile', $serverCert, '-rfc', '-validity', "$ValidityDays",
	'-ext', "SAN=$san", '-ext', 'EKU=serverAuth', '-ext', 'KU=digitalSignature,keyEncipherment',
	'-ext', 'BC=ca:false')

# 3. Put the CA and the signed certificate (full chain) into the server key store
Invoke-Keytool @('-importcert', '-alias', 'chzzk-dev-ca', '-keystore', $serverStore, '-storepass', $password,
	'-file', $caCert, '-noprompt')
Invoke-Keytool @('-importcert', '-alias', 'chzzk-dev', '-keystore', $serverStore, '-storepass', $password,
	'-file', $serverCert)

Remove-Item $request, $serverCert

Write-Host "Created in $OutputDirectory"
Write-Host "  CA certificate for the phone : $caCert"
Write-Host "  Server key store (alias chzzk-dev): $serverStore"
Write-Host "  Subject Alternative Name     : $san"
Write-Host ''
Write-Host 'Run the server with:'
Write-Host "  `$env:DEV_SSL_KEY_STORE = `"$($serverStore -replace '\\', '/')`""
Write-Host '  $env:DEV_SSL_KEY_STORE_PASSWORD = "<the password you entered>"'
Write-Host '  $env:SPRING_PROFILES_ACTIVE = "dev,https"'
Write-Host '  .\gradlew.bat bootRun'
