# Arranca el sistema contable. Requiere Java 22+.
# Primera vez: descarga el driver H2 (base de datos SQL embebida) desde Maven Central.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$h2 = 'lib/h2-2.3.232.jar'
if (-not (Test-Path $h2)) {
  New-Item -ItemType Directory -Force lib | Out-Null
  Invoke-WebRequest 'https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar' -OutFile $h2
}
java -cp $h2 App.java @args
