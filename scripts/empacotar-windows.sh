#!/usr/bin/env bash
# Gera o Lume.exe portátil para Windows 10/11 (x64) a partir de Linux ou do Git Bash:
#   1. compila o núcleo (Maven) e copia as bibliotecas;
#   2. monta um Java 21 reduzido para Windows com jlink (usando os jmods do JDK Temurin para Windows);
#   3. empacota tudo num executável só com o NSIS (makensis) e copia para a raiz do repositório.
#
# Requisitos: JDK 21, Maven 3.9, NSIS (apt install nsis / choco install nsis), unzip e python3.
# JMODS_WIN: pasta jmods de um JDK 21 para Windows (se faltar, o script baixa o Temurin do GitHub).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | sed -n 2p)"
OUT="$ROOT/target/windows"
APP="$OUT/app"
MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.sql,java.sql.rowset,java.xml,jdk.charsets,jdk.crypto.ec,jdk.jfr,jdk.unsupported"
TEMURIN="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"

echo "==> Compilando o Lume $VERSION"
(cd "$ROOT" && mvn -B -q -DskipTests package && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib)

if [ -z "${JMODS_WIN:-}" ]; then
  JMODS_WIN="$OUT/jdk-windows/jmods"
  if [ ! -d "$JMODS_WIN" ]; then
    echo "==> Baixando os jmods do Temurin 21 para Windows"
    mkdir -p "$OUT/jdk-windows"
    curl -sSL -o "$OUT/jdk-windows.zip" "$TEMURIN"
    unzip -q "$OUT/jdk-windows.zip" '*/jmods/*' -d "$OUT/jdk-windows.tmp"
    mv "$OUT"/jdk-windows.tmp/*/jmods "$JMODS_WIN"
    rm -rf "$OUT/jdk-windows.tmp" "$OUT/jdk-windows.zip"
  fi
fi

echo "==> Java reduzido (jlink)"
rm -rf "$APP"
mkdir -p "$APP/lib"
jlink --module-path "$JMODS_WIN" --add-modules "$MODULES" --output "$APP/runtime" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-0

echo "==> Núcleo e bibliotecas"
cp "$ROOT/target/lume.jar" "$APP/lume.jar"
cp "$ROOT"/target/lib/*.jar "$APP/lib/"
# O sqlite-jdbc traz bibliotecas nativas de todos os sistemas; o Lume.exe só precisa da do Windows x64.
python3 - "$APP"/lib/sqlite-jdbc-*.jar <<'PY'
import sys, zipfile, os
src = sys.argv[1]; tmp = src + ".tmp"
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename
        if name.startswith("org/sqlite/native/") and not name.startswith("org/sqlite/native/Windows/x86_64/") and not name.endswith("/"):
            continue
        zout.writestr(item, zin.read(name))
os.replace(tmp, src)
PY
cp "$ROOT/build/icon.png" "$APP/abertura.png"
cp "$ROOT/LICENSE" "$APP/LICENSE.txt"

echo "==> Executável (NSIS)"
BUILD="$VERSION-$(cd "$APP" && find . -type f -name '*.jar' -exec sha1sum {} + | sort | sha1sum | cut -c1-8)"
makensis -V2 -DVERSION="$VERSION" -DBUILD="$BUILD" -DAPPDIR="$APP" -DOUT="$OUT/Lume.exe" -DICON="$ROOT/build/icon.ico" \
  "$ROOT/packaging/windows/lume.nsi"

SIZE=$(stat -c %s "$OUT/Lume.exe" 2>/dev/null || wc -c < "$OUT/Lume.exe")
echo "Lume.exe: $((SIZE / 1048576)) MB"
if [ "$SIZE" -ge $((100 * 1048576)) ]; then
  echo "O Lume.exe passou de 100 MB (limite por arquivo do GitHub)." >&2
  exit 1
fi
cp "$OUT/Lume.exe" "$ROOT/Lume.exe"
echo "Lume.exe copiado para a raiz do repositório."
