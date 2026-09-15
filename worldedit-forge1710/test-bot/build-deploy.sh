#!/usr/bin/env bash
# Build the Forge 1.7.10 module, reject client-only Minecraft API use, deploy the jar to the test server.
#
#   ./build-deploy.sh            forge module only
#   ./build-deploy.sh --core     republish worldedit-core to mavenLocal first (after core changes)
#
# Environment: FAWE_TEST_SERVER (test server directory), FAWE_JAVA_HOME (JDK 25; the build needs it even if JAVA_HOME is older).
set -euo pipefail
MODULE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$MODULE/.." && pwd)"
SERVER="${FAWE_TEST_SERVER:-/d/MinecraftServerBlilud/fawe-testserver}"
export JAVA_HOME="${FAWE_JAVA_HOME:-/c/Program Files/Java/jdk-25.0.3}"
LOG="${TEMP:-/tmp}/fawe-build.log"

if [ "${1:-}" = "--core" ]; then
  (cd "$REPO" && ./gradlew.bat :worldedit-core:publishToMavenLocal -Pfawe.log4jApiVersion=2.0-beta9 --offline -x test -x javadoc > "$LOG.core" 2>&1) \
    || { grep -E "error:|エラー" "$LOG.core" | head -20; echo "CORE BUILD FAILED (see $LOG.core)"; exit 1; }
fi
(cd "$MODULE" && ./gradlew.bat build -x test --offline > "$LOG" 2>&1) \
  || { grep -E "error:|エラー" "$LOG" | head -20; echo "BUILD FAILED (see $LOG)"; exit 1; }

# SideOnlyCheck: references to @SideOnly(CLIENT) members compile against the merged dev jar but fail on a server.
ASM=""
for j in $(find "$SERVER/libraries/org/ow2/asm" -name "*9.9.1.jar"); do ASM="$ASM;$(cygpath -w "$j")"; done
TOOLS_OUT="$MODULE/build/tools"
if [ ! -f "$TOOLS_OUT/SideOnlyCheck.class" ] || [ "$MODULE/tools/SideOnlyCheck.java" -nt "$TOOLS_OUT/SideOnlyCheck.class" ]; then
  mkdir -p "$TOOLS_OUT"
  "$JAVA_HOME/bin/javac" -d "$(cygpath -w "$TOOLS_OUT")" -cp "${ASM:1}" "$(cygpath -w "$MODULE/tools/SideOnlyCheck.java")"
fi
RESULT=$("$JAVA_HOME/bin/java" -cp "$(cygpath -w "$TOOLS_OUT")$ASM" SideOnlyCheck \
  "$(cygpath -w "$MODULE/build/libs/FastAsyncWorldEdit-Forge1710-0.1.0-M1-dev.jar")" \
  "$(cygpath -w "$MODULE/build/rfg/recompiled_minecraft-1.7.10.jar")")
echo "$RESULT"
case "$RESULT" in problems=0*) ;; *) echo "CLIENT-ONLY API USED - not deployed"; exit 1;; esac

RUNNING=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Xmx4G' -and \$_.CommandLine -match 'Crucible' }).Count")
if [ "${RUNNING//[[:space:]]/}" != "0" ]; then
  echo "test server is running - not deployed"
  exit 1
fi
cp "$MODULE/build/libs/FastAsyncWorldEdit-Forge1710-0.1.0-M1.jar" "$SERVER/mods/"
echo "BUILD OK, deployed to $SERVER/mods"
