#!/usr/bin/env sh
# Starts the bootstrap node. Extra options pass through, e.g. --port=2000 --host=192.168.1.20
cd "$(dirname "$0")/.." || exit 1
[ -f distrilab.jar ] || scripts/build.sh || exit 1
exec java -jar distrilab.jar bootstrap "$@"
