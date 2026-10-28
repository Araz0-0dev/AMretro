; AMretro 64-bit per-user installer, compiled with Unicode NSIS 3.
; Input files are supplied with /DAPP_BINARY=..., /DWEBVIEW_LOADER=...,
; /DWEBVIEW_BOOTSTRAPPER=..., /DFONT_LICENSE=..., /DAPP_ICON=..., /DSIDEBAR=..., /DOUTPUT=...
Unicode true
RequestExecutionLevel user
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!include "FileFunc.nsh"

!ifndef APP_BINARY
  !error "APP_BINARY is required"
!endif
!ifndef WEBVIEW_LOADER
  !error "WEBVIEW_LOADER is required"
!endif
!ifndef WEBVIEW_BOOTSTRAPPER
  !error "WEBVIEW_BOOTSTRAPPER is required"
!endif
!ifndef FONT_LICENSE
  !error "FONT_LICENSE is required"
!endif
!ifndef APP_ICON
  !error "APP_ICON is required"
!endif
!ifndef SIDEBAR
  !error "SIDEBAR is required"
!endif
!ifndef OUTPUT
  !error "OUTPUT is required"
!endif

Name "AMretro"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\AMretro"
InstallDirRegKey HKCU "Software\AMretro" "InstallDir"
SetCompressor /SOLID lzma
ShowInstDetails nevershow
ShowUninstDetails nevershow
VIProductVersion "0.3.0.0"
VIAddVersionKey "ProductName" "AMretro"
VIAddVersionKey "ProductVersion" "0.3.0"
VIAddVersionKey "FileVersion" "0.3.0"
VIAddVersionKey "FileDescription" "AMretro Setup"
VIAddVersionKey "CompanyName" "AMretro"

!define MUI_ICON "${APP_ICON}"
!define MUI_UNICON "${APP_ICON}"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${SIDEBAR}"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "${SIDEBAR}"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "به نصب AMretro خوش آمدی"
!define MUI_WELCOMEPAGE_TEXT "این برنامه، AMretro را روی دستگاه نصب می‌کند. پس از نصب می‌توانی آن را از دسکتاپ یا منوی شروع باز کنی.$\r$\n$\r$\nبرای ادامه، دکمه بعدی را بزن."
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\AMretro.exe"
!define MUI_FINISHPAGE_RUN_TEXT "اجرای AMretro"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Farsi"

Function .onInit
  StrCpy $LANGUAGE ${LANG_FARSI}
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "AMretro به ویندوز ۶۴ بیتی نیاز دارد."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "AMretro به ویندوز ۱۰ یا نسخه جدیدتر نیاز دارد."
    Abort
  ${EndIf}
FunctionEnd

; Microsoft documents these two client keys for detecting the Evergreen runtime.
; A bootstrapper is embedded and downloads the runtime only if it is missing.
Function EnsureWebView2
  retry_runtime:
  SetRegView 32
  ReadRegStr $0 HKLM "SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${If} $0 == ""
  ${OrIf} $0 == "0.0.0.0"
    ReadRegStr $0 HKCU "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${EndIf}
  ${If} $0 == ""
  ${OrIf} $0 == "0.0.0.0"
    DetailPrint "Installing Microsoft Edge WebView2 Runtime..."
    InitPluginsDir
    SetOutPath "$PLUGINSDIR"
    File /oname=MicrosoftEdgeWebview2Setup.exe "${WEBVIEW_BOOTSTRAPPER}"
    ClearErrors
    ExecWait '"$PLUGINSDIR\MicrosoftEdgeWebview2Setup.exe" /silent /install' $1
    ${If} ${Errors}
      StrCpy $1 1
    ${EndIf}
    ${If} $1 != 0
      IfSilent runtime_fail_silent
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "نصب WebView2 انجام نشد. اتصال اینترنت را بررسی کن و دوباره تلاش کن. برای نصب دستی، به سایت Microsoft Edge WebView2 مراجعه کن." IDRETRY retry_runtime
      runtime_fail_silent:
      SetErrorLevel 1
      Abort
    ${EndIf}
  ${EndIf}
FunctionEnd

Section "AMretro" SEC_APP
  Call EnsureWebView2
  SetOutPath "$INSTDIR"
  SetOverwrite on
  File /oname=AMretro.exe "${APP_BINARY}"
  File /oname=WebView2Loader.dll "${WEBVIEW_LOADER}"
  File /oname=OFL-Vazirmatn.txt "${FONT_LICENSE}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\AMretro"
  CreateShortcut "$SMPROGRAMS\AMretro\AMretro.lnk" "$INSTDIR\AMretro.exe" "" "$INSTDIR\AMretro.exe" 0
  CreateShortcut "$SMPROGRAMS\AMretro\Uninstall AMretro.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\AMretro.lnk" "$INSTDIR\AMretro.exe" "" "$INSTDIR\AMretro.exe" 0

  WriteRegStr HKCU "Software\AMretro" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "DisplayName" "AMretro"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "DisplayVersion" "0.3.0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "Publisher" "AMretro"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "DisplayIcon" "$INSTDIR\AMretro.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro" "EstimatedSize" "$0"
SectionEnd

Section "Uninstall"
  ; Intentionally preserve the user's library, games, settings and other files.
  Delete "$DESKTOP\AMretro.lnk"
  Delete "$SMPROGRAMS\AMretro\AMretro.lnk"
  Delete "$SMPROGRAMS\AMretro\Uninstall AMretro.lnk"
  RMDir "$SMPROGRAMS\AMretro"
  Delete "$INSTDIR\AMretro.exe"
  Delete "$INSTDIR\WebView2Loader.dll"
  Delete "$INSTDIR\OFL-Vazirmatn.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AMretro"
  DeleteRegKey HKCU "Software\AMretro"
SectionEnd
