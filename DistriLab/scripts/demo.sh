#!/usr/bin/env sh
# Local demo: bootstrap node + N workers (default 4) in the background, logs in logs/demo/,
# then the client GUI. Stop with scripts/stop-demo.sh.
#   scripts/demo.sh        -> 4 workers
#   scripts/demo.sh 6      -> 6 workers
#   scripts/demo.sh 4 --no-gui
cd "$(dirname "$0")/.." || exit 1
[ -f distrilab.jar ] || scripts/build.sh || exit 1
WORKERS="${1:-4}"
mkdir -p logs/demo
: > logs/demo/pids
# stdin from /dev/null: a background process that reads the terminal would be suspended.
java -jar distrilab.jar bootstrap < /dev/null > logs/demo/bootstrap.log 2>&1 &
echo $! >> logs/demo/pids
sleep 2
i=1
while [ "$i" -le "$WORKERS" ]; do
  java -jar distrilab.jar worker --id="$i" --worker.console=false < /dev/null > "logs/demo/worker-$i.log" 2>&1 &
  echo $! >> logs/demo/pids
  sleep 1
  i=$((i + 1))
done
sleep 2
java -jar distrilab.jar cli status
echo
echo "Logs: logs/demo/*.log   (e.g. tail -f logs/demo/worker-1.log)"
if [ "$2" != "--no-gui" ]; then
  java -jar distrilab.jar client < /dev/null > logs/demo/client.log 2>&1 &
  echo $! >> logs/demo/pids
  echo "Client GUI started. Stop everything with scripts/stop-demo.sh"
fi
