#define AppName "Triki Music Controller"
#define AppVersion "3.1.6"
#define AppPublisher "Bartek"
#define AppExeName "TrikiMusicController.Windows.exe"
#define AppMutex "Local\TrikiMusicController.BAEDA449-C844-43F1-8888-AE0EFE5FBB13"
#define GitHubUrl "https://github.com/Baartek57548/triki-music-controller"
#ifndef PublishDirectory
#define PublishDirectory "..\artifacts\publish"
#endif

[Setup]
AppId={{BAEDA449-C844-43F1-8888-AE0EFE5FBB13}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#GitHubUrl}
AppSupportURL={#GitHubUrl}/issues
AppUpdatesURL={#GitHubUrl}/releases/latest
DefaultDirName={localappdata}\Programs\Triki Music Controller
DefaultGroupName=Triki Music Controller
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\artifacts\installer
OutputBaseFilename=triki-music-controller-windows-v{#AppVersion}-setup
SetupIconFile=..\TrikiMusicController.Windows\Assets\AppIcon.ico
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern dynamic
CloseApplications=yes
RestartApplications=yes
AppMutex={#AppMutex}
MinVersion=10.0.22000
VersionInfoVersion={#AppVersion}.0
VersionInfoCompany={#AppPublisher}
VersionInfoDescription=Instalator aplikacji {#AppName}
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#AppVersion}
ChangesAssociations=no
ChangesEnvironment=no

[Languages]
Name: "polish"; MessagesFile: "compiler:Languages\Polish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Utwórz skrót na pulpicie"; GroupDescription: "Dodatkowe skróty:"; Flags: unchecked
Name: "startup"; Description: "Uruchamiaj aplikację razem z Windows"; GroupDescription: "Automatyczne łączenie:"; Flags: checkedonce

[Files]
Source: "{#PublishDirectory}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs restartreplace

[Icons]
Name: "{group}\Triki Music Controller"; Filename: "{app}\{#AppExeName}"
Name: "{group}\Odinstaluj Triki Music Controller"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Triki Music Controller"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\TrikiMusicController\Updates"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "TrikiMusicController"; Flags: deletevalue
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "TrikiMusicController"; ValueData: """{app}\{#AppExeName}"" --background"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Uruchom Triki Music Controller"; Flags: nowait postinstall skipifsilent
Filename: "{app}\{#AppExeName}"; Parameters: "--whats-new"; Flags: nowait; Check: WizardSilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM {#AppExeName} /F"; Flags: runhidden; RunOnceId: "StopTrikiMusicController"

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
  if not IsWin64 then
  begin
    MsgBox('Ta wersja aplikacji wymaga 64-bitowego systemu Windows 11.', mbError, MB_OK);
    Result := False;
  end;
end;
