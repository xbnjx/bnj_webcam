<#
.SYNOPSIS
    Script automatizado para compilar BNJ WebCam y generar el instalador en Windows.
.DESCRIPTION
    1. Verifica el entorno de Python y dependencias.
    2. Ejecuta PyInstaller para empaquetar el ejecutable BNJ_WebCam_Client.exe.
    3. Compila el instalador con Inno Setup (ISCC.exe).
    4. Genera el instalador final en client/installer_output_webcam/BNJ_WebCam_Setup.exe.
#>

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
$ClientDir = Join-Path $ProjectRoot "client"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  Compilando BNJ WebCam para Windows (10 / 11 64-bit)" -ForegroundColor Cyan
Write-Host "=========================================================" -ForegroundColor Cyan

# 1. Detectar Python / Virtualenv
$PythonExe = Join-Path $ProjectRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $PythonExe)) {
    $PythonExe = "python"
}
Write-Host "[1/3] Usando Python: $PythonExe" -ForegroundColor Green

# 2. Compilar con PyInstaller
Write-Host "[2/3] Generando ejecutable con PyInstaller..." -ForegroundColor Green
Set-Location $ClientDir

& $PythonExe -m PyInstaller `
  --noconfirm `
  --windowed `
  --onedir `
  --name BNJ_WebCam_Client `
  --icon "assets\icon.ico" `
  --distpath "." `
  --workpath "build\pyinstaller" `
  --specpath "." `
  --collect-all customtkinter `
  --collect-all pyvirtualcam `
  --add-data "assets;assets" `
  --add-data "drivers;drivers" `
  --add-data "adb;adb" `
  "main.py"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Falló la compilación con PyInstaller."
    exit $LASTEXITCODE
}

# 3. Compilar instalador con Inno Setup
Write-Host "[3/3] Compilando instalador Inno Setup..." -ForegroundColor Green
$InnoCompiler = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if (-not (Test-Path $InnoCompiler)) {
    $InnoCompiler = "C:\Program Files\Inno Setup 6\ISCC.exe"
}

if (Test-Path $InnoCompiler) {
    & $InnoCompiler ".\bnj_webcam.iss"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Falló la compilación del instalador Inno Setup."
        exit $LASTEXITCODE
    }
    Write-Host "=========================================================" -ForegroundColor Green
    Write-Host "  ¡Instalador generado con éxito!" -ForegroundColor Green
    Write-Host "  Ubicación: $ClientDir\installer_output_webcam\BNJ_WebCam_Setup.exe" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
} else {
    Write-Warning "Inno Setup no fue encontrado en las rutas estándar. El ejecutable compilado está disponible en: $ClientDir\BNJ_WebCam_Client\"
}

Set-Location $ProjectRoot
