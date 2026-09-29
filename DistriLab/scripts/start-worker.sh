#!/usr/bin/env sh
# Starts one worker. First argument = worker ID (optional); further options pass through.
#   scripts/start-worker.sh 3
#   scripts/start-worker.sh 3 --threads=8 --host=192.168.1.21 --bootstrap=192.168.1.20:1099
cd "$(dirname "$0")/.." || exit 1
[ -f distrilab.jar ] || scripts/build.sh || exit 1
if [ $# -eq 0 ]; then
  exec java -jar distrilab.jar worker
fi
id="$1"; shift
exec java -jar distrilab.jar worker --id="$id" "$@"
