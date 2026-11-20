#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" \
  "$ROOT/src/main/java/dev/chainforge/core/SimulationLock.java" \
  "$ROOT/src/main/java/dev/chainforge/solana/SolanaKeys.java" \
  "$ROOT/src/test/java/dev/chainforge/CoreSelfTest.java"
java -cp "$OUT" dev.chainforge.CoreSelfTest
