#!/usr/bin/env bash
# Compila y prueba EditParams.kt" "$here/../../app/src/main/java/com/rawpocket/PresetLogic.kt (lógica de la lista de recientes) en PC, sin Android.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
lib="${KOTLIN_LIB:-$(ls -d /opt/gradle-*/lib | head -1)}"
out="$(mktemp -d)"
ver="$(ls "$lib"/kotlin-compiler-embeddable-*.jar | sed -E 's/.*embeddable-(.*)\.jar/\1/' | head -1)"
cp_jars="$(ls "$lib"/kotlin-stdlib-$ver.jar "$lib"/kotlin-script-runtime-$ver.jar "$lib"/kotlin-reflect-*.jar "$lib"/trove4j*.jar "$lib"/annotations-*.jar "$lib"/kotlinx-coroutines-core-jvm-*.jar | tr '\n' ':')"
mkdir -p "$out/stub/org/json" && cp "$here/stub/JSONObject.kt" "$out/stub/org/json/"
java -cp "$lib/kotlin-compiler-embeddable-$ver.jar:$cp_jars" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$lib/kotlin-stdlib-$ver.jar" -d "$out/classes" \
  "$out/stub/org/json/JSONObject.kt" "$here/../../app/src/main/java/com/rawpocket/EditParams.kt" "$here/../../app/src/main/java/com/rawpocket/PresetLogic.kt" "$here/PresetCheck.kt"
java -cp "$out/classes:$lib/kotlin-stdlib-$ver.jar" PresetCheckKt
