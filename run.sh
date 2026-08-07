#!/usr/bin/env bash
# MyTix: compile and run the application in one step.
# Usage (from the project root):  ./run.sh
#
# On Windows, run this from Git Bash (not WSL), so javac/java from
# your Windows JDK are on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAR="mysql-connector-java-8.0.29.jar"
if [[ ! -f "$JAR" ]]; then
  echo "error: $JAR not found in $ROOT" >&2
  exit 1
fi

# Locate javac / java (PATH, then common Windows locations under Git Bash / WSL).
resolve_java_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  local candidates=(
    "/c/Program Files/Common Files/Oracle/Java/javapath/${name}.exe"
    "/mnt/c/Program Files/Common Files/Oracle/Java/javapath/${name}.exe"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

if ! JAVAC="$(resolve_java_tool javac)"; then
  echo "error: javac not found on PATH." >&2
  echo "Install a JDK, or on Windows open Git Bash (not WSL) and retry." >&2
  exit 1
fi
if ! JAVA="$(resolve_java_tool java)"; then
  echo "error: java not found on PATH." >&2
  exit 1
fi

# Windows JVMs need ';' in the classpath; Unix JVMs need ':'.
SEP=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
esac
if [[ "$JAVA" == *.exe ]] || [[ "$JAVA" == */mnt/c/* ]]; then
  SEP=";"
fi

mkdir -p out
echo "Compiling with: $JAVAC"
"$JAVAC" -cp "$JAR" -d out \
  src/Main.java \
  src/db/*.java \
  src/util/*.java \
  src/service/*.java

echo "Starting MyTix..."
exec "$JAVA" -cp "out${SEP}${JAR}" Main
