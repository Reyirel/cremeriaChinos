<#
Genera un ejecutable de Windows autocontenido (no requiere Java instalado en la PC destino).

Uso:
    powershell -ExecutionPolicy Bypass -File packaging\empaquetar.ps1
    powershell -ExecutionPolicy Bypass -File packaging\empaquetar.ps1 -Instalador   # ademas genera un instalador .exe (requiere WiX Toolset)

Resultado:
    target\dist\image\PuntoVentaCremerias\   -> carpeta portable, copiar/zipear y correr PuntoVentaCremerias.exe
    target\dist\installer\                   -> instalador .exe (solo con -Instalador)
#>
param(
    [switch]$Instalador
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Find-Jpackage {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
        return "$env:JAVA_HOME\bin\jpackage.exe"
    }
    $candidatos = Get-ChildItem "C:\Program Files\Java\*\bin\jpackage.exe", "C:\Program Files\Eclipse Adoptium\*\bin\jpackage.exe" -ErrorAction SilentlyContinue
    if ($candidatos) { return $candidatos[0].FullName }
    throw "No se encontro jpackage.exe. Instala un JDK 17+ o define JAVA_HOME."
}

$jpackage = Find-Jpackage
Write-Host "Usando jpackage: $jpackage"

Write-Host "`n== Compilando (sin pruebas) =="
& .\mvnw.cmd -q clean package "-Dmaven.test.skip=true"
if ($LASTEXITCODE -ne 0) { throw "Fallo la compilacion" }

Write-Host "`n== Reuniendo dependencias =="
Remove-Item -Recurse -Force "target\app-input" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "target\app-input" | Out-Null
& .\mvnw.cmd -q dependency:copy-dependencies "-DoutputDirectory=target\app-input" "-DincludeScope=runtime"
if ($LASTEXITCODE -ne 0) { throw "Fallo copiando dependencias" }

$appJar = Get-ChildItem "target\puntoVentaCremerias-*.jar" | Select-Object -First 1
Copy-Item $appJar.FullName "target\app-input\"

Write-Host "`n== Generando app-image (carpeta portable con Java incluido) =="
Remove-Item -Recurse -Force "target\dist\image" -ErrorAction SilentlyContinue
& $jpackage `
    --type app-image `
    --input "target\app-input" `
    --dest "target\dist\image" `
    --name "PuntoVentaCremerias" `
    --main-jar $appJar.Name `
    --main-class "com.cremerias.puntoventa.Launcher" `
    --icon "packaging\icon.ico" `
    --app-version "1.0.0" `
    --vendor "Cremerias Chinos" `
    --description "Punto de venta Cremerias Chinos"
if ($LASTEXITCODE -ne 0) { throw "Fallo jpackage (app-image)" }

Write-Host "`nListo: target\dist\image\PuntoVentaCremerias\PuntoVentaCremerias.exe"

if ($Instalador) {
    Write-Host "`n== Generando instalador .exe (requiere WiX Toolset) =="
    Remove-Item -Recurse -Force "target\dist\installer" -ErrorAction SilentlyContinue
    & $jpackage `
        --type exe `
        --input "target\app-input" `
        --dest "target\dist\installer" `
        --name "PuntoVentaCremerias" `
        --main-jar $appJar.Name `
        --main-class "com.cremerias.puntoventa.Launcher" `
        --icon "packaging\icon.ico" `
        --app-version "1.0.0" `
        --vendor "Cremerias Chinos" `
        --description "Punto de venta Cremerias Chinos" `
        --win-shortcut `
        --win-menu `
        --win-dir-chooser
    if ($LASTEXITCODE -ne 0) { throw "Fallo jpackage (instalador). Esta instalado WiX Toolset?" }
    Write-Host "`nListo: target\dist\installer\"
}
