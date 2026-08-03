param(
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 5000,
    [string]$Username = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $projectRoot "frontend"
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"

Push-Location $frontendPath
try {
    npm install
    npm run build
} finally {
    Pop-Location
}

Push-Location $projectRoot
try {
    & $mavenWrapper test
    & $mavenWrapper javafx:run "-Djavafx.args=--host $HostAddress --port $Port --username $Username"
} finally {
    Pop-Location
}
