import os
import sys
import time
import logging
import threading
from pathlib import Path
from typing import Optional, Tuple

import numpy as np
from PIL import Image

try:
    import pyvirtualcam
except ImportError:
    pyvirtualcam = None

logger = logging.getLogger("BNJ_WebCam.VCam")

# OBS Virtual Camera CLSID in Windows Registry
OBS_VIRTUALCAM_CLSID = r"SOFTWARE\Classes\CLSID\{A3FCE0F5-3493-419F-958A-ABA1250EC20B}"


def is_driver_installed() -> bool:
    """Checks if the required virtual camera driver is available on the OS."""
    if sys.platform == "win32":
        try:
            import winreg
            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, OBS_VIRTUALCAM_CLSID)
            winreg.CloseKey(key)
            return True
        except OSError:
            return False
    elif sys.platform.startswith("linux"):
        # Check if v4l2loopback module is loaded or devices exist
        return Path("/sys/module/v4l2loopback").exists() or any(
            Path("/dev").glob("video*")
        )
    return False


def install_windows_driver(driver_dir: Path) -> Tuple[bool, str]:
    """
    Registers the 64-bit and 32-bit OBS Virtual Camera DirectShow filters
    using regsvr32 with elevated administrator privileges.
    """
    if sys.platform != "win32":
        return False, "Esta función solo está disponible en Windows."

    dll64 = driver_dir / "obs-virtualcam-module64.dll"
    dll32 = driver_dir / "obs-virtualcam-module32.dll"

    if not dll64.exists():
        return False, f"No se encontró el driver de 64 bits en: {dll64}"

    import subprocess

    commands = [f'regsvr32.exe /i /s "{dll64}"']
    if dll32.exists():
        commands.append(f'regsvr32.exe /i /s "{dll32}"')

    full_cmd = " & ".join(commands)
    ps_cmd = f'Start-Process cmd.exe -ArgumentList "/c {full_cmd}" -Verb RunAs -Wait'

    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_cmd],
            capture_output=True,
            text=True,
            timeout=20,
        )
        time.sleep(0.5)
        if is_driver_installed():
            return True, "Controlador de Cámara Virtual registrado correctamente."
        else:
            return False, f"El registro no completó: {result.stderr or result.stdout}"
    except Exception as e:
        return False, f"Error al ejecutar elevación UAC: {e}"


class VirtualCameraManager:
    """Manages the lifecycle and frame output of the virtual camera device."""

    def __init__(self):
        self.lock = threading.Lock()
        self.cam: Optional["pyvirtualcam.Camera"] = None
        self.is_active = False
        self.width = 0
        self.height = 0
        self.target_fps = 30
        self.frames_sent = 0
        self.last_fps_check = time.time()
        self.fps_counter = 0
        self.current_fps = 0.0
        self.device_name = ""

        if sys.platform == "win32":
            self.backend = "obs"
        elif sys.platform.startswith("linux"):
            self.backend = "v4l2loopback"
        else:
            self.backend = None

    def start(self, width: int, height: int, fps: int = 30) -> Tuple[bool, str]:
        with self.lock:
            if pyvirtualcam is None:
                return False, "La biblioteca pyvirtualcam no está instalada."

            if not is_driver_installed():
                if sys.platform == "win32":
                    return False, "El controlador OBS Virtual Camera no está registrado en Windows."
                elif sys.platform.startswith("linux"):
                    return False, "El módulo v4l2loopback no está cargado en el sistema."

            if self.cam is not None and self.width == width and self.height == height:
                self.is_active = True
                return True, f"Cámara virtual activa ({self.device_name})"

            self._close_camera_locked()

            try:
                self.cam = pyvirtualcam.Camera(
                    width=width,
                    height=height,
                    fps=fps,
                    fmt=pyvirtualcam.PixelFormat.RGB,
                    backend=self.backend,
                )
                self.width = width
                self.height = height
                self.target_fps = fps
                self.device_name = self.cam.device or "Virtual Camera"
                self.is_active = True
                self.frames_sent = 0
                self.fps_counter = 0
                self.last_fps_check = time.time()
                return True, f"Cámara virtual iniciada: {self.device_name} ({width}x{height} @ {fps}fps)"
            except Exception as e:
                self.is_active = False
                return False, f"No se pudo iniciar la cámara virtual: {e}"

    def send_frame(self, frame_input) -> bool:
        """
        Sends a frame to the virtual camera. Accepts PIL.Image or np.ndarray.
        Automatically handles dynamic dimension changes.
        """
        if not self.is_active:
            return False

        # Convert to numpy array RGB uint8
        if isinstance(frame_input, Image.Image):
            if frame_input.mode != "RGB":
                frame_input = frame_input.convert("RGB")
            arr = np.asarray(frame_input)
            h, w = arr.shape[:2]
        elif isinstance(frame_input, np.ndarray):
            arr = frame_input
            h, w = arr.shape[:2]
        else:
            return False

        with self.lock:
            if not self.is_active:
                return False

            # If dimensions changed (e.g. phone flipped from landscape to portrait)
            if self.cam is None or self.width != w or self.height != h:
                ok, msg = self.start(w, h, self.target_fps)
                if not ok:
                    logger.warning(msg)
                    return False

            try:
                self.cam.send(arr)
                self.frames_sent += 1
                self.fps_counter += 1

                now = time.time()
                elapsed = now - self.last_fps_check
                if elapsed >= 1.0:
                    self.current_fps = round(self.fps_counter / elapsed, 1)
                    self.fps_counter = 0
                    self.last_fps_check = now

                return True
            except Exception as e:
                logger.error(f"Error al enviar fotograma a la cámara virtual: {e}")
                self._close_camera_locked()
                return False

    def stop(self):
        with self.lock:
            self._close_camera_locked()

    def _close_camera_locked(self):
        self.is_active = False
        if self.cam is not None:
            try:
                self.cam.close()
            except Exception:
                pass
            self.cam = None
        self.width = 0
        self.height = 0
        self.current_fps = 0.0
