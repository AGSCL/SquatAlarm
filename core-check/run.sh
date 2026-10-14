#!/usr/bin/env bash
# Compila y prueba el núcleo puro (SquatCounter + Calibration + Tuner/BayesTuner/BetaDist) con el Kotlin
# embebido de Gradle, sin SDK de Android: concatena las fuentes en un script y ejecuta los asserts de test-body.kts.
set -euo pipefail
cd "$(dirname "$0")"
GRADLE="${GRADLE:-gradle}"
mkdir -p build
{
  echo "import kotlin.math.*"
  for f in SquatCounter.kt Calibration.kt Tuner.kt; do
    grep -v -E '^(package |import kotlin\.math)' "../app/src/main/java/com/squatalarm/app/squat/$f"
  done
  cat test-body.kts
} > build/build.gradle.kts
: > build/settings.gradle.kts   # build independiente del proyecto Android
"$GRADLE" -q --offline -p build coreTest
