#!/usr/bin/env sh
# Builds, runs the unit tests, then the end-to-end cluster test (own cluster on port 1299).
cd "$(dirname "$0")/.." || exit 1
scripts/build.sh || exit 1
java -jar distrilab.jar test || exit 1
java -jar distrilab.jar cluster-test
