#!/usr/bin/env sh
# Stops the processes started by scripts/demo.sh.
cd "$(dirname "$0")/.." || exit 1
if [ -f logs/demo/pids ]; then
  while read -r pid; do kill "$pid" 2>/dev/null; done < logs/demo/pids
  rm -f logs/demo/pids
fi
echo "Stopped the demo processes."
