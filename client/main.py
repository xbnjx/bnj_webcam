import io
import json
import logging
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import tkinter as tk
import webbrowser
from pathlib import Path

import customtkinter as ctk
import numpy as np
from PIL import Image, ImageTk

# Logging configuration
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("BNJ_WebCam")

# Resolve local imports across development and frozen executables
try:
    from vcam_manager import (
        VirtualCameraManager,
        is_driver_installed,
        install_windows_driver,
    )
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from vcam_manager import (
        VirtualCameraManager,
        is_driver_installed,
        install_windows_driver,
    )


def resource_path(relative_path):
    """Resolve assets in development, subdirectories and PyInstaller builds."""
    base_path = getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)
    base = Path(base_path)
    candidates = [
        base / relative_path,
        base / "assets" / relative_path,
    ]
    if getattr(sys, "frozen", False):
        exe_parent = Path(sys.executable).resolve().parent
        candidates.extend([
            exe_parent / relative_path,
            exe_parent / "assets" / relative_path,
        ])
    for cand in candidates:
        if cand.exists():
            return cand
    return candidates[0]


def get_adb_executable():
    """Detect platform-specific ADB executable path."""
    if sys.platform == "win32":
        bundled = resource_path("adb/windows/adb.exe")
        if bundled.exists():
            return bundled
        return Path("adb")
    else:
        which_adb = shutil.which("adb")
        if which_adb:
            return Path(which_adb)
        bundled_linux = resource_path("adb/linux/adb")
        if bundled_linux.exists():
            return bundled_linux
        return Path("adb")


def settings_path():
    """Retorna la ruta del archivo de configuración JSON en la carpeta del usuario ~/bnj-webcam/settings.json."""
    return Path.home() / "bnj-webcam" / "settings.json"


def legacy_settings_paths():
    """Rutas de versiones anteriores para migración automática y transparente."""
    candidates = []
    if sys.platform == "win32":
        roaming = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
        candidates.append(roaming / "BNJ WebCam" / "settings.json")
        candidates.append(roaming / "BNJ Mic&Cam" / "settings.json")
    else:
        config_dir = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
        candidates.append(config_dir / "bnj_webcam" / "settings.json")
    return candidates


def load_settings():
    defaults = {
        "adb_serial": None,
        "adb_model": None,
        "brightness": 0,
        "contrast": 1.0,
        "exposure": 0,
        "bitrate": 2000,
        "fps": 30,
    }
    path = settings_path()
    try:
        source_path = path
        if not source_path.exists():
            for legacy in legacy_settings_paths():
                if legacy.exists():
                    source_path = legacy
                    break

        if source_path.exists():
            data = json.loads(source_path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                defaults.update({
                    "adb_serial": data.get("adb_serial") if isinstance(data.get("adb_serial"), str) else None,
                    "adb_model": data.get("adb_model") if isinstance(data.get("adb_model"), str) else None,
                    "brightness": int(data.get("brightness", 0)),
                    "contrast": float(data.get("contrast", 1.0)),
                    "exposure": int(data.get("exposure", 0)),
                    "bitrate": int(data.get("bitrate", 2000)),
                    "fps": int(data.get("fps", 30)),
                })
        return defaults, None
    except Exception as error:
        return defaults, f"No se pudo leer {path}: {error}"


ADB_EXE = get_adb_executable()
HOST, PORT = "127.0.0.1", 12345
VIDEO_PACKET = 1
SETTINGS_PACKET = 3
MAX_VIDEO_PACKET = 64 * 1024 * 1024
ADB_REFRESH_INTERVAL_SECONDS = 3
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def adjust_image_frame(arr: np.ndarray, brightness: int, contrast: float) -> np.ndarray:
    """
    Applies real-time brightness and contrast adjustment using fast vector math.
    Takes ~0.3ms on a 720p frame.
    """
    if brightness == 0 and abs(contrast - 1.0) < 0.01:
        return arr
    try:
        f = arr.astype(np.float32)
        if abs(contrast - 1.0) >= 0.01:
            f = (f - 128.0) * contrast + 128.0
        if brightness != 0:
            f += brightness * 2.0
        return np.clip(f, 0, 255).astype(np.uint8)
    except Exception:
        return arr


class BNJWebCamApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("BNJ WebCam - Cliente Profesional")
        self.geometry("740x940")
        self.minsize(660, 820)
        ctk.set_appearance_mode("Dark")
        ctk.set_default_color_theme("blue")

        self.settings, settings_error = load_settings()
        self.adb_devices = {}
        self.adb_device_labels = {}
        self.adb_selected_serial = self.settings.get("adb_serial")
        self.streaming_serial = None
        self.adb_scan_lock = threading.Lock()
        self.is_streaming = False
        self.stop_event = threading.Event()
        self.server_sock = None
        self.conn = None
        self.socket_lock = threading.Lock()

        # Image & transmission parameters
        self.fps_var = tk.StringVar(value=str(self.settings.get("fps", 30)))
        self.bitrate_var = tk.IntVar(value=self.settings.get("bitrate", 2000))
        self.exposure_var = tk.IntVar(value=self.settings.get("exposure", 0))
        self.brightness_var = tk.IntVar(value=self.settings.get("brightness", 0))
        self.contrast_var = tk.DoubleVar(value=self.settings.get("contrast", 1.0))

        # Virtual camera is ALWAYS active
        self.vcam_manager = VirtualCameraManager()
        self.driver_installed = is_driver_installed()

        # Preview window state
        self.video_window = None
        self.video_label = None
        self.preview_image = None
        self.latest_frame = None
        self.rendered_frame = None
        self.latest_frame_lock = threading.Lock()
        self.video_aspect_ratio = None
        self.video_window_size = None
        self.video_geometry_adjusting = False
        self._save_after_id = None

        self._setup_ui()
        self.after(33, self._render_pending_video_frame)
        self.after(1000, self._update_stream_stats_loop)

        if settings_error:
            self._set_status(settings_error, "#F44336")

        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self._refresh_adb_devices_now()
        self.after(ADB_REFRESH_INTERVAL_SECONDS * 1000, self._poll_adb_devices)

    def _setup_ui(self):
        # Window icon
        try:
            icon_path = resource_path("icon.ico")
            if icon_path.exists() and sys.platform == "win32":
                self.iconbitmap(str(icon_path))
        except Exception:
            pass

        # Scrollable container to guarantee comfortable viewing on any resolution
        main_scroll = ctk.CTkScrollableFrame(self, fg_color="transparent")
        main_scroll.pack(fill="both", expand=True, padx=4, pady=4)

        # Header branding
        header_frame = ctk.CTkFrame(main_scroll, fg_color="transparent")
        header_frame.pack(fill="x", pady=(10, 4))
        logo_path = resource_path("icon.png")
        if logo_path.exists():
            try:
                logo = ctk.CTkImage(Image.open(logo_path), size=(52, 52))
                ctk.CTkLabel(header_frame, image=logo, text="").pack(pady=(2, 2))
            except Exception:
                pass
        ctk.CTkLabel(header_frame, text="BNJ WebCam", font=("Helvetica", 24, "bold")).pack()
        ctk.CTkLabel(
            header_frame,
            text="Transforma tu teléfono en una cámara web profesional USB (Cámara Virtual activa).",
            font=("Helvetica", 13),
            text_color="#94A3B8",
        ).pack(pady=(2, 8))

        # Card: ADB Connection
        adb_frame = ctk.CTkFrame(main_scroll, corner_radius=10)
        adb_frame.pack(padx=16, pady=6, fill="x")
        ctk.CTkLabel(
            adb_frame, text="Dispositivo Android (USB / ADB)", font=("Helvetica", 13, "bold")
        ).pack(anchor="w", padx=14, pady=(10, 4))

        adb_controls = ctk.CTkFrame(adb_frame, fg_color="transparent")
        adb_controls.pack(fill="x", padx=14, pady=(0, 10))
        self.adb_device_menu = ctk.CTkOptionMenu(
            adb_controls,
            values=["Buscando dispositivos ADB..."],
            command=self._on_adb_device_change,
            height=34,
        )
        self.adb_device_menu.pack(side="left", fill="x", expand=True, padx=(0, 8))
        ctk.CTkButton(
            adb_controls, text="Actualizar", width=95, height=34, command=self._refresh_adb_devices_now
        ).pack(side="right")

        # Action Buttons (Connect / Open Preview)
        actions = ctk.CTkFrame(main_scroll, fg_color="transparent")
        actions.pack(pady=6)
        self.btn_stream = ctk.CTkButton(
            actions,
            text="Conectar teléfono",
            font=("Helvetica", 13, "bold"),
            command=self.toggle_streaming,
            width=210,
            height=38,
        )
        self.btn_stream.grid(row=0, column=0, padx=8)

        self.btn_preview = ctk.CTkButton(
            actions,
            text="Abrir Vista Previa",
            font=("Helvetica", 13),
            command=self._open_video_window,
            width=180,
            height=38,
            fg_color="#334155",
            hover_color="#475569",
        )
        self.btn_preview.grid(row=0, column=1, padx=8)

        # Status & Virtual Camera Info Card
        status_card = ctk.CTkFrame(main_scroll, corner_radius=10, fg_color="#1E293B")
        status_card.pack(padx=16, pady=6, fill="x")

        self.lbl_status = ctk.CTkLabel(
            status_card,
            text="Estado: buscando dispositivo ADB...",
            font=("Helvetica", 13, "bold"),
            text_color="#FBBF24",
        )
        self.lbl_status.pack(pady=(10, 2))

        self.lbl_stream_stats = ctk.CTkLabel(
            status_card,
            text="Resolución: -- | FPS VCam: -- | Cuadros: 0",
            font=("Helvetica", 11),
            text_color="#94A3B8",
        )
        self.lbl_stream_stats.pack(pady=(0, 6))

        # Driver banner
        self.driver_status_frame = ctk.CTkFrame(status_card, fg_color="#0F172A", corner_radius=8)
        self.driver_status_frame.pack(fill="x", padx=12, pady=(2, 10))
        self.lbl_driver_info = ctk.CTkLabel(
            self.driver_status_frame,
            text="",
            font=("Helvetica", 12),
        )
        self.lbl_driver_info.pack(side="left", padx=10, pady=6)
        self.btn_install_driver = ctk.CTkButton(
            self.driver_status_frame,
            text="Instalar Driver",
            width=120,
            height=28,
            fg_color="#2563EB",
            hover_color="#1D4ED8",
            command=self._install_virtualcam_driver,
        )
        self._update_driver_ui()

        # Image & Stream Settings Card
        settings_card = ctk.CTkFrame(main_scroll, corner_radius=10)
        settings_card.pack(padx=16, pady=6, fill="x")

        settings_header = ctk.CTkFrame(settings_card, fg_color="transparent")
        settings_header.pack(fill="x", padx=14, pady=(10, 6))
        ctk.CTkLabel(
            settings_header,
            text="Ajustes de Imagen y Transmisión",
            font=("Helvetica", 13, "bold"),
        ).pack(side="left")

        ctk.CTkButton(
            settings_header,
            text="Restablecer",
            width=85,
            height=24,
            font=("Helvetica", 11),
            fg_color="#475569",
            hover_color="#64748B",
            command=self._reset_adjustments,
        ).pack(side="right")

        controls_grid = ctk.CTkFrame(settings_card, fg_color="transparent")
        controls_grid.pack(fill="x", padx=14, pady=(0, 12))

        # 1. FPS selector
        row0 = ctk.CTkFrame(controls_grid, fg_color="transparent")
        row0.pack(fill="x", pady=4)
        ctk.CTkLabel(row0, text="Tasa de Cuadros (FPS):", width=180, anchor="w", font=("Helvetica", 12)).pack(side="left")
        self.fps_selector = ctk.CTkSegmentedButton(
            row0,
            values=["25", "30", "48", "60"],
            variable=self.fps_var,
            command=self._on_fps_change,
        )
        self.fps_selector.pack(side="right", fill="x", expand=True)

        # 2. Bitrate slider (800 - 5000 kbps)
        row1 = ctk.CTkFrame(controls_grid, fg_color="transparent")
        row1.pack(fill="x", pady=4)
        self.lbl_bitrate_val = ctk.CTkLabel(row1, text=f"Bitrate ({self.bitrate_var.get()} kbps):", width=180, anchor="w", font=("Helvetica", 12))
        self.lbl_bitrate_val.pack(side="left")
        self.slider_bitrate = ctk.CTkSlider(
            row1,
            from_=800,
            to=5000,
            number_of_steps=42,
            variable=self.bitrate_var,
            command=self._on_bitrate_slider,
        )
        self.slider_bitrate.pack(side="right", fill="x", expand=True)

        # 3. Exposure compensation slider (-4 .. +4)
        row2 = ctk.CTkFrame(controls_grid, fg_color="transparent")
        row2.pack(fill="x", pady=4)
        self.lbl_exposure_val = ctk.CTkLabel(row2, text=f"Exposición ({self.exposure_var.get():+d} EV):", width=180, anchor="w", font=("Helvetica", 12))
        self.lbl_exposure_val.pack(side="left")
        self.slider_exposure = ctk.CTkSlider(
            row2,
            from_=-4,
            to=4,
            number_of_steps=8,
            variable=self.exposure_var,
            command=self._on_exposure_slider,
        )
        self.slider_exposure.pack(side="right", fill="x", expand=True)

        # 4. Brightness slider (-50 .. +50)
        row3 = ctk.CTkFrame(controls_grid, fg_color="transparent")
        row3.pack(fill="x", pady=4)
        self.lbl_brightness_val = ctk.CTkLabel(row3, text=f"Brillo Digital ({self.brightness_var.get():+d}):", width=180, anchor="w", font=("Helvetica", 12))
        self.lbl_brightness_val.pack(side="left")
        self.slider_brightness = ctk.CTkSlider(
            row3,
            from_=-50,
            to=50,
            number_of_steps=50,
            variable=self.brightness_var,
            command=self._on_brightness_slider,
        )
        self.slider_brightness.pack(side="right", fill="x", expand=True)

        # 5. Contrast slider (0.5 .. 1.5)
        row4 = ctk.CTkFrame(controls_grid, fg_color="transparent")
        row4.pack(fill="x", pady=4)
        self.lbl_contrast_val = ctk.CTkLabel(row4, text=f"Contraste Digital ({self.contrast_var.get():.2f}x):", width=180, anchor="w", font=("Helvetica", 12))
        self.lbl_contrast_val.pack(side="left")
        self.slider_contrast = ctk.CTkSlider(
            row4,
            from_=0.5,
            to=1.5,
            number_of_steps=20,
            variable=self.contrast_var,
            command=self._on_contrast_slider,
        )
        self.slider_contrast.pack(side="right", fill="x", expand=True)

        # Social Links & BNJ Community
        links = ctk.CTkFrame(main_scroll, fg_color="transparent")
        links.pack(pady=(8, 14))
        for column, (icon, text, url) in enumerate((
            ("yt.png", "YouTube", "https://www.youtube.com/@BenjaminDuarteIUTUB"),
            ("web.png", "Web Oficial", "https://benjaminduarte.lat/"),
            ("wsp.png", "WhatsApp", "https://whatsapp.com/channel/0029VbDqWzFCHDycOjbgfu41"),
        )):
            path = resource_path(icon)
            image = ctk.CTkImage(Image.open(path), size=(20, 20)) if path.exists() else None
            ctk.CTkButton(
                links,
                text=text,
                image=image,
                compound="left",
                command=lambda target=url: webbrowser.open(target),
                fg_color="transparent",
                border_width=1,
                border_color="#475569",
                height=32,
            ).grid(row=0, column=column, padx=6, pady=4)

    def _update_driver_ui(self):
        try:
            self.driver_installed = is_driver_installed()
            if self.driver_installed:
                backend_label = "OBS Virtual Camera (DirectShow)" if sys.platform == "win32" else "v4l2loopback (Linux)"
                self.lbl_driver_info.configure(
                    text=f"✓ Cámara Virtual lista y siempre activa ({backend_label})",
                    text_color="#4ADE80",
                )
                self.btn_install_driver.pack_forget()
            else:
                if sys.platform == "win32":
                    self.lbl_driver_info.configure(
                        text="⚠ El driver DirectShow no está registrado.",
                        text_color="#F87171",
                    )
                    self.btn_install_driver.pack(side="right", padx=10, pady=6)
                else:
                    self.lbl_driver_info.configure(
                        text="⚠ Requiere el módulo v4l2loopback cargado en Linux.",
                        text_color="#F87171",
                    )
                    self.btn_install_driver.pack_forget()
        except Exception as e:
            logger.warning(f"Error actualizando UI de driver: {e}")

    def _install_virtualcam_driver(self):
        try:
            drivers_dir = resource_path("drivers")
            self._set_status("Solicitando permisos de Administrador para registrar el driver...", "#FBBF24")
            ok, msg = install_windows_driver(drivers_dir)
            self._update_driver_ui()
            if ok:
                self._set_status("¡Driver de Cámara Virtual instalado con éxito!", "#4ADE80")
            else:
                self._set_status(f"Error al instalar driver: {msg}", "#F87171")
        except Exception as e:
            self._set_status(f"Excepción al instalar driver: {e}", "#F87171")

    def _on_fps_change(self, value):
        self.settings["fps"] = int(value)
        self._trigger_settings_save_and_sync()

    def _on_bitrate_slider(self, value):
        val = int(value)
        self.lbl_bitrate_val.configure(text=f"Bitrate ({val} kbps):")
        self.settings["bitrate"] = val
        self._trigger_settings_save_and_sync()

    def _on_exposure_slider(self, value):
        val = int(value)
        self.lbl_exposure_val.configure(text=f"Exposición ({val:+d} EV):")
        self.settings["exposure"] = val
        self._trigger_settings_save_and_sync()

    def _on_brightness_slider(self, value):
        val = int(value)
        self.lbl_brightness_val.configure(text=f"Brillo Digital ({val:+d}):")
        self.settings["brightness"] = val
        self._settings_changed()

    def _on_contrast_slider(self, value):
        val = float(value)
        self.lbl_contrast_val.configure(text=f"Contraste Digital ({val:.2f}x):")
        self.settings["contrast"] = val
        self._settings_changed()

    def _reset_adjustments(self):
        self.fps_var.set("30")
        self.bitrate_var.set(2000)
        self.exposure_var.set(0)
        self.brightness_var.set(0)
        self.contrast_var.set(1.0)
        self.lbl_bitrate_val.configure(text="Bitrate (2000 kbps):")
        self.lbl_exposure_val.configure(text="Exposición (0 EV):")
        self.lbl_brightness_val.configure(text="Brillo Digital (0):")
        self.lbl_contrast_val.configure(text="Contraste Digital (1.00x):")
        self.settings.update({
            "fps": 30,
            "bitrate": 2000,
            "exposure": 0,
            "brightness": 0,
            "contrast": 1.0,
        })
        self._trigger_settings_save_and_sync()

    def _trigger_settings_save_and_sync(self):
        self._settings_changed()
        if self.is_streaming:
            self._send_settings_packet()

    def _settings_changed(self):
        if self._save_after_id is not None:
            try:
                self.after_cancel(self._save_after_id)
            except Exception:
                pass
        self._save_after_id = self.after(350, self._save_settings)

    def _save_settings(self):
        self._save_after_id = None
        try:
            path = settings_path()
            temporary = path.with_suffix(".tmp")
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary.write_text(
                json.dumps(self.settings, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            temporary.replace(path)
        except Exception as error:
            logger.warning(f"No se pudo guardar la configuración: {error}")

    def _send_settings_packet(self):
        """Sends updated FPS, Bitrate, and Exposure compensation parameters to Android."""
        connection = self.conn
        if connection is None:
            return
        try:
            fps = int(self.fps_var.get())
            bitrate = int(self.bitrate_var.get())
            exposure = int(self.exposure_var.get())
            payload = struct.pack(">III", fps, bitrate, exposure)
            with self.socket_lock:
                connection.sendall(bytes((SETTINGS_PACKET,)) + struct.pack(">I", len(payload)) + payload)
        except Exception as error:
            logger.warning(f"No se pudo enviar configuración a Android: {error}")

    def _set_status(self, text, color="#FBBF24"):
        if not self.stop_event.is_set():
            try:
                self.after(0, lambda: self.lbl_status.configure(text=text, text_color=color))
            except Exception:
                pass

    @staticmethod
    def parse_adb_devices(output):
        devices = {}
        for line in output.splitlines():
            parts = line.strip().split()
            if (
                len(parts) < 2
                or parts[0] == "List"
                or parts[1] not in {"device", "offline", "unauthorized"}
            ):
                continue
            serial, state = parts[:2]
            properties = {}
            for part in parts[2:]:
                if ":" in part:
                    key, value = part.split(":", 1)
                    properties[key] = value
            devices[serial] = {
                "serial": serial,
                "model": properties.get("model", "Modelo desconocido").replace("_", " "),
                "product": properties.get("product"),
                "state": state,
            }
        return devices

    def _query_adb_devices(self):
        try:
            result = subprocess.run(
                [str(ADB_EXE), "devices", "-l"],
                capture_output=True,
                text=True,
                creationflags=NO_WINDOW,
                timeout=6,
                check=True,
            )
            return self.parse_adb_devices(result.stdout)
        except Exception as e:
            logger.warning(f"Error al consultar ADB: {e}")
            return {}

    def _refresh_adb_devices_now(self):
        threading.Thread(target=self._scan_adb_devices, name="miccam-adb-scan", daemon=True).start()

    def _scan_adb_devices(self):
        if not self.adb_scan_lock.acquire(blocking=False):
            return
        try:
            devices = self._query_adb_devices()
            if not self.stop_event.is_set():
                self.after(0, lambda: self._update_adb_device_menu(devices))
        except Exception as exc:
            if not self.stop_event.is_set():
                self.after(0, lambda: self._set_status(f"Error consultando ADB: {exc}", "#F87171"))
        finally:
            self.adb_scan_lock.release()

    def _poll_adb_devices(self):
        if self.stop_event.is_set():
            return
        self._refresh_adb_devices_now()
        self.after(ADB_REFRESH_INTERVAL_SECONDS * 1000, self._poll_adb_devices)

    def _update_adb_device_menu(self, devices):
        previous_serial = self.adb_selected_serial
        if self.is_streaming:
            selected_device = devices.get(self.streaming_serial)
            if selected_device is None or selected_device["state"] != "device":
                self._stop_streaming()
                self._set_status("El teléfono seleccionado se desconectó de ADB.", "#F87171")
        self.adb_devices = devices
        labels = {}
        for serial, device in devices.items():
            state_label = "" if device["state"] == "device" else f" · {device['state']}"
            labels[serial] = f"{device['model']} · {serial}{state_label}"

        if previous_serial and previous_serial not in labels:
            saved_model = self.settings.get("adb_model") or "Dispositivo desconectado"
            labels[previous_serial] = f"{saved_model} · {previous_serial} · desconectado"
        if not labels:
            labels[""] = "No se encontraron dispositivos ADB"
        self.adb_device_labels = labels
        menu_values = list(labels.values())
        self.adb_device_menu.configure(values=menu_values)

        selected = previous_serial if previous_serial in labels else None
        if selected is None:
            available = [
                serial for serial, device in devices.items()
                if device["state"] == "device"
            ]
            if len(available) == 1:
                selected = available[0]
        self.adb_selected_serial = selected
        if self.is_streaming and selected != self.streaming_serial:
            self._stop_streaming()
        self.adb_device_menu.set(labels.get(selected or "", menu_values[0]))

        if selected != previous_serial:
            self.settings["adb_serial"] = selected
            self.settings["adb_model"] = self.adb_devices.get(selected, {}).get("model")
            self._settings_changed()

        if selected and selected not in devices and not self.is_streaming:
            self._set_status(f"El dispositivo seleccionado ({selected}) no está conectado.", "#FBBF24")
        elif selected is None and devices and not self.is_streaming:
            self._set_status("Selecciona un dispositivo ADB autorizado.", "#FBBF24")
        elif not devices and not self.is_streaming:
            self._set_status("Conecta el teléfono por USB y autoriza la depuración ADB.")

    def _on_adb_device_change(self, label):
        selected = next(
            (serial for serial, device_label in self.adb_device_labels.items() if device_label == label),
            None,
        )
        if selected == self.adb_selected_serial:
            return
        if self.is_streaming:
            self._stop_streaming()
        self.adb_selected_serial = selected
        self.settings["adb_serial"] = selected
        self.settings["adb_model"] = self.adb_devices.get(selected, {}).get("model")
        self._settings_changed()
        if selected:
            device = self.adb_devices.get(selected)
            if device is None:
                self._set_status(f"El dispositivo seleccionado ({selected}) no está conectado.", "#FBBF24")
                return
            state = device["state"]
            status = "conectado" if state == "device" else f"estado {state}"
            self._set_status(
                f"Seleccionado {device['model']} ({selected}), {status}.",
                "#4ADE80" if state == "device" else "#FBBF24",
            )

    def toggle_streaming(self):
        if self.is_streaming:
            self._stop_streaming()
            return
        serial = self.adb_selected_serial
        device = self.adb_devices.get(serial)
        if device is None or device["state"] != "device":
            self._set_status("Selecciona un teléfono ADB autorizado antes de conectar.", "#F87171")
            return
        self.is_streaming = True
        self.streaming_serial = serial
        self.btn_stream.configure(text="Desconectar teléfono", fg_color="#DC2626", hover_color="#B91C1C")
        threading.Thread(
            target=self._receive_stream,
            args=(serial,),
            name="miccam-receiver",
            daemon=True,
        ).start()

    def _receive_stream(self, serial):
        failed = False
        try:
            subprocess.run(
                [str(ADB_EXE), "-s", serial, "reverse", f"tcp:{PORT}", f"tcp:{PORT}"],
                check=True, capture_output=True, text=True,
                creationflags=NO_WINDOW, timeout=8,
            )
            self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_sock.bind((HOST, PORT))
            self.server_sock.listen(1)
            self.server_sock.settimeout(1)
            self._set_status("Servidor USB listo. Inicia la cámara desde la app Android.", "#4ADE80")

            while self.is_streaming and not self.stop_event.is_set():
                try:
                    self.conn, _ = self.server_sock.accept()
                    break
                except socket.timeout:
                    continue
            if self.conn is None or not self.is_streaming:
                return

            connection = self.conn
            connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 256 * 1024)

            # Envía de inmediato el apretón de manos con los ajustes actuales
            self._send_settings_packet()
            self._set_status("Teléfono conectado. Transmitiendo a Cámara Virtual...", "#4ADE80")

            # Inicia de inmediato la Cámara Virtual (siempre activa)
            fps_int = int(self.fps_var.get())
            ok, msg = self.vcam_manager.start(1280, 720, fps_int)
            if not ok:
                self._set_status(f"Aviso Cámara Virtual: {msg}", "#FBBF24")

            while self.is_streaming and not self.stop_event.is_set():
                packet_type = self._recv_exact(connection, 1)
                if packet_type is None:
                    break
                size_bytes = self._recv_exact(connection, 4)
                if size_bytes is None:
                    raise ConnectionError("Conexión interrumpida al leer el tamaño de la trama")
                size = int.from_bytes(size_bytes, byteorder="big", signed=False)
                packet_id = packet_type[0]
                limits = {VIDEO_PACKET: MAX_VIDEO_PACKET}
                if packet_id not in limits or not 0 < size <= limits[packet_id]:
                    raise ValueError(f"Trama no válida (tipo={packet_id}, tamaño={size})")
                payload = self._recv_exact(connection, size)
                if payload is None:
                    raise ConnectionError("Conexión interrumpida al leer la trama")
                if packet_id == VIDEO_PACKET:
                    self._process_video_packet(payload)

        except (OSError, ValueError, ConnectionError, subprocess.SubprocessError) as error:
            failed = True
            if self.is_streaming and not self.stop_event.is_set():
                self._set_status(f"Conexión finalizada: {error}", "#F87171")
        except Exception as generic_error:
            failed = True
            logger.error(f"Error inesperado en streaming: {generic_error}")
        finally:
            was_receiving = self.is_streaming
            self.is_streaming = False
            self.streaming_serial = None
            self.vcam_manager.stop()
            self._close_sockets()
            if not self.stop_event.is_set():
                def finish_ui():
                    self.btn_stream.configure(
                        text="Conectar teléfono",
                        fg_color=["#3B8ED0", "#1F6AA5"],
                        hover_color=["#36719F", "#144870"],
                        state="normal",
                    )
                    if was_receiving and not failed:
                        self.lbl_status.configure(
                            text="Teléfono desconectado. Puedes volver a conectar.",
                            text_color="#FBBF24",
                        )
                try:
                    self.after(0, finish_ui)
                except Exception:
                    pass

    @staticmethod
    def _recv_exact(connection, size):
        data = bytearray()
        try:
            while len(data) < size:
                chunk = connection.recv(size - len(data))
                if not chunk:
                    return None
                data.extend(chunk)
            return bytes(data)
        except Exception:
            return None

    def _process_video_packet(self, jpeg_bytes):
        try:
            source = Image.open(io.BytesIO(jpeg_bytes))
            image = source.convert("RGB")
            arr = np.asarray(image)

            # Ajuste de brillo y contraste en tiempo real
            b = self.brightness_var.get()
            c = self.contrast_var.get()
            processed_arr = adjust_image_frame(arr, b, c)

            # Envío a la Cámara Virtual (siempre activa)
            self.vcam_manager.send_frame(processed_arr)

            # Almacenar para la vista previa
            with self.latest_frame_lock:
                self.latest_frame = processed_arr
        except Exception as error:
            logger.debug(f"Error procesando fotograma: {error}")

    def _render_pending_video_frame(self):
        if self.stop_event.is_set():
            return
        try:
            with self.latest_frame_lock:
                frame_arr = self.latest_frame
            if frame_arr is not None and frame_arr is not self.rendered_frame:
                if self.video_window is not None and self.video_window.winfo_exists():
                    self.rendered_frame = frame_arr
                    self._render_video_frame(frame_arr)
        except Exception as e:
            logger.debug(f"Error renderizando vista previa: {e}")
        finally:
            if not self.stop_event.is_set():
                self.after(33, self._render_pending_video_frame)

    def _update_stream_stats_loop(self):
        if self.stop_event.is_set():
            return
        try:
            if self.is_streaming and self.rendered_frame is not None:
                h, w = self.rendered_frame.shape[:2]
                vcam_fps = self.vcam_manager.current_fps
                frames = self.vcam_manager.frames_sent
                status_text = f"Resolución: {w}x{h} | FPS VCam: {vcam_fps} | Cuadros: {frames}"
                self.lbl_stream_stats.configure(text=status_text)
            elif not self.is_streaming:
                self.lbl_stream_stats.configure(text="Resolución: -- | FPS VCam: -- | Cuadros: 0")
        except Exception:
            pass
        finally:
            if not self.stop_event.is_set():
                self.after(1000, self._update_stream_stats_loop)

    def _ensure_video_window(self):
        if self.video_window is not None and self.video_window.winfo_exists():
            return
        self.video_window = ctk.CTkToplevel(self)
        self.video_window.title("BNJ WebCam - Vista Previa")
        self.video_window.geometry("960x540")
        self.video_window.minsize(320, 180)
        self.video_window.bind("<Configure>", self._resize_video_window)
        try:
            icon = resource_path("icon.ico")
            if icon.exists() and sys.platform == "win32":
                self.video_window.iconbitmap(str(icon))
        except Exception:
            pass
        self.video_label = tk.Label(
            self.video_window,
            borderwidth=0,
            highlightthickness=0,
            padx=0,
            pady=0,
            background="#000000",
        )
        self.video_label.pack(fill="both", expand=True)
        self.video_window.protocol("WM_DELETE_WINDOW", self.video_window.withdraw)

    def _open_video_window(self):
        try:
            self._ensure_video_window()
            self.video_window.deiconify()
            self.video_window.lift()
        except Exception as e:
            logger.warning(f"No se pudo abrir ventana de video: {e}")

    def _resize_video_window(self, event):
        if event.widget is not self.video_window or self.rendered_frame is None:
            return
        if self.video_geometry_adjusting:
            return

        width, height = max(1, event.width), max(1, event.height)
        if width <= 1 or height <= 1:
            return
        frame_width, frame_height = self.video_aspect_ratio or (16, 9)
        if abs(width * frame_height - height * frame_width) > max(frame_width, frame_height):
            previous_size = self.video_window_size or (width, height)
            width_change = abs(width - previous_size[0])
            height_change = abs(height - previous_size[1])
            if width_change >= height_change:
                height = round(width * frame_height / frame_width)
            else:
                width = round(height * frame_width / frame_height)
            self.video_window_size = (width, height)
            self.video_geometry_adjusting = True
            try:
                self.video_window.geometry(f"{width}x{height}")
            finally:
                self.video_geometry_adjusting = False
            return

        self.video_window_size = (width, height)
        self._render_video_frame(self.rendered_frame)

    def _render_video_frame(self, frame_arr):
        if self.video_window is None or not self.video_window.winfo_exists():
            return
        try:
            fh, fw = frame_arr.shape[:2]
            width = max(1, self.video_label.winfo_width())
            height = max(1, self.video_label.winfo_height())
            aspect_ratio = (fw, fh)
            if self.video_aspect_ratio != aspect_ratio:
                self.video_aspect_ratio = aspect_ratio
                try:
                    self.video_window.aspect(aspect_ratio[0], aspect_ratio[1], aspect_ratio[0], aspect_ratio[1])
                except tk.TclError:
                    pass
                min_width = max(320, round(180 * aspect_ratio[0] / aspect_ratio[1]))
                min_height = max(180, round(320 * aspect_ratio[1] / aspect_ratio[0]))
                self.video_window.minsize(min_width, min_height)
                width = max(1, self.video_label.winfo_width())
                if width <= 1:
                    width = max(960, min_width)
                else:
                    width = max(width, min_width)
                height = round(width * aspect_ratio[1] / aspect_ratio[0])
                self.video_window_size = (width, height)
                self.video_window.geometry(f"{width}x{height}")

            image = Image.fromarray(frame_arr)
            preview = image.resize((width, height), Image.Resampling.BILINEAR)
            self.preview_image = ImageTk.PhotoImage(preview)
            self.video_label.configure(image=self.preview_image, text="")
        except Exception as e:
            logger.debug(f"Error renderizando cuadro: {e}")

    def _stop_streaming(self):
        self.is_streaming = False
        try:
            self.btn_stream.configure(state="disabled")
        except Exception:
            pass
        self.vcam_manager.stop()
        self._close_sockets()
        self._set_status("Recepción detenida.")

    def _close_sockets(self):
        for connection in (self.conn, self.server_sock):
            if connection is not None:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                try:
                    connection.close()
                except OSError:
                    pass
        self.conn = None
        self.server_sock = None

    def on_close(self):
        try:
            if self._save_after_id is not None:
                self.after_cancel(self._save_after_id)
                self._save_after_id = None
                self._save_settings()
        except Exception:
            pass
        self.stop_event.set()
        self.is_streaming = False
        self.vcam_manager.stop()
        self._close_sockets()
        try:
            self.destroy()
        except Exception:
            pass


if __name__ == "__main__":
    BNJWebCamApp().mainloop()
