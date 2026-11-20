[Setup]
AppName=BNJ WebCam
AppVersion=1.0.0
AppPublisher=Benjamín Duarte (BNJ)
AppPublisherURL=https://benjaminduarte.lat/
AppSupportURL=https://whatsapp.com/channel/0029VbDqWzFCHDycOjbgfu41
DefaultDirName={autopf}\BNJ WebCam
DefaultGroupName=BNJ WebCam
OutputDir=.\installer_output_webcam
OutputBaseFilename=BNJ_WebCam_Setup
SetupIconFile=assets\setup.ico
Compression=lzma2/max
SolidCompression=yes
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog
WizardStyle=modern
; Limita la instalación únicamente a Windows 10 y Windows 11 de 64 bits
MinVersion=10.0
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
UninstallDisplayIcon={app}\icon.ico

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Files]
; Copia la aplicación compilada con PyInstaller
Source: "BNJ_WebCam_Client\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Archivos multimedia y recursos
Source: "assets\*"; DestDir: "{app}\assets"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "assets\icon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\icon.png"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\yt.png"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\web.png"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\wsp.png"; DestDir: "{app}"; Flags: ignoreversion
; Controladores DirectShow de Cámara Virtual
Source: "drivers\*"; DestDir: "{app}\drivers"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: "desktopicon"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"
Name: "startupicon"; Description: "Iniciar BNJ WebCam automáticamente al iniciar Windows"; GroupDescription: "Opciones avanzadas:"; Flags: unchecked

[Icons]
Name: "{group}\BNJ WebCam"; Filename: "{app}\BNJ_WebCam_Client.exe"; IconFilename: "{app}\icon.ico"
Name: "{group}\Desinstalar BNJ WebCam"; Filename: "{uninstallexe}"
Name: "{autodesktop}\BNJ WebCam"; Filename: "{app}\BNJ_WebCam_Client.exe"; Tasks: desktopicon; IconFilename: "{app}\icon.ico"
Name: "{commonstartup}\BNJ WebCam"; Filename: "{app}\BNJ_WebCam_Client.exe"; Tasks: startupicon; IconFilename: "{app}\icon.ico"

[Run]
; Registro de filtros DirectShow de 64 bits y 32 bits para compatibilidad total con apps de 32 y 64 bits
Filename: "regsvr32.exe"; Parameters: "/i /s ""{app}\drivers\obs-virtualcam-module64.dll"""; StatusMsg: "Registrando controlador DirectShow de Cámara Virtual (64-bit)..."; Flags: runascurrentuser
Filename: "regsvr32.exe"; Parameters: "/i /s ""{app}\drivers\obs-virtualcam-module32.dll"""; StatusMsg: "Registrando controlador DirectShow de Cámara Virtual (32-bit)..."; Flags: runascurrentuser
Filename: "{app}\BNJ_WebCam_Client.exe"; Description: "Ejecutar BNJ WebCam ahora"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Desregistro limpio de los filtros DirectShow durante la desinstalación
Filename: "regsvr32.exe"; Parameters: "/u /s ""{app}\drivers\obs-virtualcam-module64.dll"""; RunOnceId: "UnregVirtualCam64"; Flags: runascurrentuser
Filename: "regsvr32.exe"; Parameters: "/u /s ""{app}\drivers\obs-virtualcam-module32.dll"""; RunOnceId: "UnregVirtualCam32"; Flags: runascurrentuser
