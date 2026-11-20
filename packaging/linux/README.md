# Empaquetado para Linux (Debian / Ubuntu)

Esta carpeta contiene la estructura y scripts necesarios para generar el paquete instalador estándar `.deb` de **BNJ WebCam** compatible con distribuciones basadas en Debian (Ubuntu, Linux Mint, Debian, Pop!_OS, Zorin OS, etc.).

## Estructura del Paquete

* `build_deb.sh`: Script automatizado para armar la jerarquía del paquete y compilarlo con `dpkg-deb`.
* `DEBIAN/`: Metadatos del paquete.
  * `control`: Define nombre, versión, arquitectura (`all`), mantenedor y dependencias (`python3`, `adb`, `v4l2loopback-dkms`, etc.).
  * `postinst`: Script post-instalación que configura el módulo de kernel `v4l2loopback` en `/etc/modules-load.d/`, asigna `/dev/video10` con la etiqueta "BNJ WebCam", e instala las reglas `udev` para acceso USB sin permisos de superusuario.
  * `prerm`: Script previo a la desinstalación que limpia las configuraciones de kernel y udev.
* `usr/bin/bnj-webcam`: Lanzador ejecutable global.
* `usr/share/applications/bnj-webcam.desktop`: Acceso directo para el menú de aplicaciones de GNOME, KDE, XFCE, etc.
* `usr/share/icons/`: Icono oficial de la aplicación para el sistema.

## Generación del Paquete

En un sistema Debian o Ubuntu:

```bash
chmod +x build_deb.sh
./build_deb.sh
```

El instalador `BNJ_WebCam_1.0.0_all.deb` se generará en la raíz del proyecto.

## Instalación

```bash
sudo apt update
sudo apt install ./BNJ_WebCam_1.0.0_all.deb
```
