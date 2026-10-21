; Lume.exe: lançador portátil (um arquivo só, sem instalador).
; Na primeira execução extrai o Java reduzido (jlink), o lume.jar e as bibliotecas para
; %LOCALAPPDATA%\Lume\app\<versão>; nas seguintes só abre. A pasta do Lume.exe vai em LUME_EXE_DIR
; (modo portátil: Lume-dados ao lado do executável) e os argumentos ("Abrir com") seguem para o Java.

Unicode true
!include "FileFunc.nsh"

!ifndef VERSION
  !define VERSION "2.0.0"
!endif
!ifndef BUILD
  !define BUILD "${VERSION}"
!endif

Name "Lume"
Caption "Lume"
OutFile "${OUT}"
Icon "${ICON}"
RequestExecutionLevel user
SilentInstall silent
SetCompressor /SOLID lzma
SetCompressorDictSize 64
SetDatablockOptimize on
CRCCheck on
ShowInstDetails nevershow

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "Lume"
VIAddVersionKey "FileDescription" "Lume - biblioteca de PDFs e DOCX"
VIAddVersionKey "CompanyName" "Erik Pimenta e Clara Portes"
VIAddVersionKey "LegalCopyright" "Copyright (©) 2026 Erik Pimenta e Clara Portes"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "OriginalFilename" "Lume.exe"

Section
  StrCpy $0 "$LOCALAPPDATA\Lume\app\${BUILD}"
  IfFileExists "$0\pronto.txt" abrir
    Banner::show /NOUNLOAD /set 76 "Lume" "Preparando o Lume pela primeira vez..."
    RMDir /r "$0"
    SetOutPath "$0"
    File /r "${APPDIR}\*.*"
    FileOpen $1 "$0\pronto.txt" w
    FileWrite $1 "${BUILD}"
    FileClose $1
    Banner::destroy
  abrir:
  ${GetParameters} $R0
  System::Call 'Kernel32::SetEnvironmentVariable(t "LUME_EXE_DIR", t "$EXEDIR")i'
  SetOutPath "$0"
  Exec '"$0\runtime\bin\javaw.exe" -splash:"$0\abertura.png" -Xss1m -Dfile.encoding=UTF-8 -Dsun.java2d.d3d=false -cp "$0\lume.jar;$0\lib\*" io.github.ravelroot.lume.LumeApplication $R0'
SectionEnd
