#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Construyendo paquete Debian .deb para BNJ WebCam ==="

# 1. Crear directorios de destino en el paquete
mkdir -p usr/share/bnj-webcam
mkdir -p usr/share/icons/hicolor/256x256/apps
mkdir -p usr/share/applications
mkdir -p usr/bin

# 2. Copiar código fuente y activos desde client/
CLIENT_DIR="../../client"
if [ -d "$CLIENT_DIR" ]; then
    echo "Copiando archivos del cliente..."
    cp -f "$CLIENT_DIR/main.py" usr/share/bnj-webcam/
    cp -f "$CLIENT_DIR/vcam_manager.py" usr/share/bnj-webcam/
    cp -f "$CLIENT_DIR/requirements.txt" usr/share/bnj-webcam/
    # Copiar archivos multimedia y activos
    if [ -d "$CLIENT_DIR/assets" ]; then
        cp -rf "$CLIENT_DIR/assets" usr/share/bnj-webcam/
        cp -f "$CLIENT_DIR/assets/icon.png" usr/share/icons/hicolor/256x256/apps/bnj-webcam.png
    fi
    for img in icon.png yt.png web.png wsp.png; do
        if [ -f "$CLIENT_DIR/$img" ]; then
            cp -f "$CLIENT_DIR/$img" usr/share/bnj-webcam/
        elif [ -f "$CLIENT_DIR/assets/$img" ]; then
            cp -f "$CLIENT_DIR/assets/$img" usr/share/bnj-webcam/
        fi
    done
fi

# 3. Ajustar permisos según estándares Debian
echo "Ajustando permisos de archivos..."
chmod 755 DEBIAN/postinst DEBIAN/prerm usr/bin/bnj-webcam
chmod 644 DEBIAN/control usr/share/applications/bnj-webcam.desktop
find usr/share/bnj-webcam -type f -exec chmod 644 {} +

# 4. Compilar paquete con dpkg-deb
OUTPUT_DEB="../../BNJ_WebCam_1.0.0_all.deb"
echo "Generando paquete deb en: $OUTPUT_DEB..."
dpkg-deb --build --root-owner-group . "$OUTPUT_DEB"

echo "========================================================="
echo "  ¡Paquete .deb generado con éxito!"
echo "  Archivo: $OUTPUT_DEB"
echo "  Para instalar en Debian/Ubuntu:"
echo "    sudo apt install ./BNJ_WebCam_1.0.0_all.deb"
echo "========================================================="
