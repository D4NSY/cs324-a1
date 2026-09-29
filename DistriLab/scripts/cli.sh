#!/usr/bin/env sh
# Command-line client: scripts/cli.sh status | submit PRIMESUM 1 1000 | batch data/jobs-batch.csv ...
cd "$(dirname "$0")/.." || exit 1
[ -f distrilab.jar ] || scripts/build.sh || exit 1
exec java -jar distrilab.jar cli "$@"
