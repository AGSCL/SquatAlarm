#!/usr/bin/env bash
# Compila y prueba el código puro (contador, motor bayesiano, Beta, respaldo de parámetros, regla del sensor)
# con el Kotlin embebido de Gradle, sin SDK de Android: concatena las fuentes y ejecuta los asserts de test-body.kts.
# `run.sh sim-body.kts` corre en cambio la simulación de usuarios (no es una prueba: imprime una tabla).
set -euo pipefail
cd "$(dirname "$0")"
GRADLE="${GRADLE:-gradle}"
SRC=../app/src/main/java/com/squatalarm/app
mkdir -p build
{
  echo "import kotlin.math.*"
  for f in squat/SquatCounter.kt squat/Calibration.kt squat/Tuner.kt squat/Evolver.kt alarm/PrefsCodec.kt alarm/RingPlan.kt alarm/CheckIn.kt; do
    grep -v -E '^(package |import kotlin\.math)' "$SRC/$f"
  done
  cat "${1:-test-body.kts}"
} > build/build.gradle.kts
: > build/settings.gradle.kts   # build independiente del proyecto Android
"$GRADLE" -q --offline -p build coreTest
