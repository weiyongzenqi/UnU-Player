#define AppName "UnU-Player"
#define AppPublisher "UnU-Player"
#define AppExeName "UnU-Player.exe"

#ifndef AppVersion
  #define AppVersion "0.1.5"
#endif

#ifndef AppImage
  #define AppImage "..\..\desktopApp\build\compose\binaries\main-release\app\UnU-Player"
#endif

#ifndef OutputDir
  #define OutputDir "..\..\desktopApp\build\compose\binaries\main-release\exe"
#endif

#ifndef IconFile
  #define IconFile "..\..\desktopApp\icons\icon.ico"
#endif

[Setup]
AppId={{A720D50A-E10E-4C63-BDE2-B4D37D10E606}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\UnU-Player
DefaultGroupName=UnU-Player
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableDirPage=yes
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
UsePreviousGroup=yes
UsePreviousTasks=yes
Uninstallable=yes
UninstallFilesDir={app}\uninstall
UninstallDisplayIcon={app}\{#AppExeName}
OutputDir={#OutputDir}
OutputBaseFilename=UnU-Player-Setup-{#AppVersion}-x64
SetupIconFile={#IconFile}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
CloseApplicationsFilter=*.*
RestartApplications=no
AlwaysRestart=no
RestartIfNeededByRun=no
AppMutex=UnUPlayerDesktop_A720D50AE10E4C63BDE2B4D37D10E606
ChangesAssociations=no
ChangesEnvironment=no

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "startmenuicon"; Description: "创建开始菜单快捷方式"; GroupDescription: "创建快捷方式："
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "创建快捷方式："; Flags: unchecked

[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: files; Name: "{app}\{#AppExeName}"
Type: files; Name: "{userprograms}\UnU-Player\UnU-Player.lnk"
Type: files; Name: "{userdesktop}\UnU-Player.lnk"
Type: dirifempty; Name: "{userprograms}\UnU-Player"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; GPLv3 许可证副本随程序分发(GPLv3 §4 要求): 仓库根 LICENSE -> 安装目录 LICENSE.txt
Source: "..\..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
; 第三方组件许可证清单(Apache-2.0 全文 + GPL/LGPL 组件归属)随程序分发
Source: "..\..\THIRD-PARTY-LICENSES.txt"; DestDir: "{app}"; DestName: "THIRD-PARTY-LICENSES.txt"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\UnU-Player\UnU-Player"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: startmenuicon
Name: "{userdesktop}\UnU-Player"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[UninstallDelete]
Type: dirifempty; Name: "{userprograms}\UnU-Player"

[Code]
var
  DeleteUserData: Boolean;

function HasCommandLineSwitch(const ExpectedSwitch: String): Boolean;
var
  Index: Integer;
begin
  Result := False;
  for Index := 1 to ParamCount do
  begin
    if CompareText(ParamStr(Index), ExpectedSwitch) = 0 then
    begin
      Result := True;
      Exit;
    end;
  end;
end;

function CurrentLocalAppDataDir: String;
begin
  Result := RemoveBackslashUnlessRoot(ExpandConstant('{localappdata}'));
end;

function ExpectedUserDataDir: String;
begin
  Result := AddBackslash(CurrentLocalAppDataDir) + 'UnU-Player';
end;

function IsExpectedUserDataDir(const TargetPath: String): Boolean;
var
  NormalizedTarget: String;
begin
  NormalizedTarget := RemoveBackslashUnlessRoot(TargetPath);
  Result :=
    (CompareText(NormalizedTarget, ExpectedUserDataDir) = 0) and
    (CompareText(ExtractFileDir(NormalizedTarget), CurrentLocalAppDataDir) = 0) and
    (CompareText(ExtractFileName(NormalizedTarget), 'UnU-Player') = 0);
end;

procedure ReportUserDataDeleteFailure(const TargetPath: String);
var
  ErrorMessage: String;
begin
  ErrorMessage := '未能完整删除 UnU-Player 用户数据：' + TargetPath;
  Log(ErrorMessage);
  if not UninstallSilent then
    MsgBox(ErrorMessage, mbError, MB_OK);
end;

function InitializeUninstall: Boolean;
var
  Choice: Integer;
begin
  Result := True;
  DeleteUserData := False;

  if UninstallSilent then
  begin
    DeleteUserData := HasCommandLineSwitch('/DELETEUSERDATA');
    Exit;
  end;

  Choice := MsgBox(
    '是否同时删除当前用户的媒体库、WebDAV 连接、设置、海报缓存和日志？' + #13#10 + #13#10 +
    '选择“是”将删除：' + ExpectedUserDataDir + #13#10 +
    '选择“否”将保留全部用户数据。' + #13#10 +
    '选择“取消”将停止卸载。',
    mbConfirmation,
    MB_YESNOCANCEL or MB_DEFBUTTON2);

  if Choice = IDYES then
    DeleteUserData := True
  else if Choice = IDNO then
    DeleteUserData := False
  else
    Result := False;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  TargetPath: String;
begin
  if (CurUninstallStep <> usPostUninstall) or (not DeleteUserData) then
    Exit;

  TargetPath := ExpectedUserDataDir;
  if not IsExpectedUserDataDir(TargetPath) then
  begin
    ReportUserDataDeleteFailure(TargetPath);
    Exit;
  end;

  if DirExists(TargetPath) and (not DelTree(TargetPath, True, True, True)) then
    ReportUserDataDeleteFailure(TargetPath);
end;

function UninstallNeedRestart: Boolean;
begin
  Result := False;
end;
