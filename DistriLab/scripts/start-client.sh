#!/usr/bin/env sh
# Starts the client GUI (run it several times for several clients).
cd "$(dirname "$0")/.." || exit 1
[ -f distrilab.jar ] || scripts/build.sh || exit 1
exec java -jar distrilab.jar client "$@"
