# Empaquetado para Windows

Esta carpeta contiene las instrucciones y herramientas de automatización para generar el paquete ejecutable y el instalador de **BNJ WebCam** para Windows 10 y Windows 11 (64-bit).

## Requisitos

1. **Python 3.10+** (o el entorno virtual `.venv` configurado).
2. **Dependencias del cliente**:
   ```powershell
   pip install -r ..\..\client\requirements.txt pyinstaller
   ```
3. **Inno Setup 6** (instalado en `C:\Program Files (x86)\Inno Setup 6\` o `C:\Program Files\Inno Setup 6\`).

## Generación Automática

Ejecuta el script PowerShell incluido desde esta carpeta o desde la raíz del proyecto:

```powershell
powershell -ExecutionPolicy Bypass -File .\packaging\windows\build_installer.ps1
```

Este script se encarga automáticamente de:
1. Compilar el cliente Python a formato ejecutable standalone mediante **PyInstaller**.
2. Empaquetar los activos (`assets`), drivers DirectShow (`drivers`) y binarios ADB (`adb`).
3. Compilar el instalador nativo de Windows mediante **Inno Setup** (`client/bnj_webcam.iss`).
4. Producir el instalador final en `client/installer_output_webcam/BNJ_WebCam_Setup.exe`.
