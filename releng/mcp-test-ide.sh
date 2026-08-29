#!/usr/bin/env bash
#
# Runs MCP script files against a throwaway Eclipse IDE.
#
# Starts the IDE on a workspace of its own, on a port of its own, waits for the
# server, runs the scripts, and takes the IDE down again. Nothing touches the
# workspace anybody is working in, and a failed run leaves no process behind.
#
# Usage: releng/mcp-test-ide.sh [options] <script.json> [<script.json> ...]
#
#   --ide DIR        The Eclipse installation to run. Required unless
#                    ECLIPSE_TEST_IDE is set.
#   --port N         Port for the test server, default 8743. It must not be the
#                    one a working IDE uses, which is the whole point.
#   --workspace DIR  Use this workspace instead of a temporary one, and keep it.
#   --keep           Do not delete the temporary workspace, for looking at the
#                    log after a failure.
#   --junit FILE     Passed to the runner.
#   --timeout SEC    How long to wait for the server to come up, default 180.
#
# The installation is REUSED, not copied: only the workspace is fresh, which is
# 120 KB against a 553 MB install. So the workspace state is isolated and the
# installed bundles are not, and a bundle somebody substituted into that
# installation is what this IDE runs too. Pass an installation of its own, or
# add -configuration <dir>, when a test must not see that.

set -euo pipefail

ide=${ECLIPSE_TEST_IDE:-}
port=8743
workspace=
keep=0
junit=
timeout=180
scripts=()

while [ $# -gt 0 ]; do
  case "$1" in
    --ide) ide=$2; shift 2;;
    --port) port=$2; shift 2;;
    --workspace) workspace=$2; keep=1; shift 2;;
    --keep) keep=1; shift;;
    --junit) junit=$2; shift 2;;
    --timeout) timeout=$2; shift 2;;
    -h|--help) sed -n '2,22p' "$0"; exit 0;;
    *) scripts+=("$1"); shift;;
  esac
done

[ -n "$ide" ] || { echo "Give --ide <installation> or set ECLIPSE_TEST_IDE." >&2; exit 2; }
[ -x "$ide/eclipse" ] || { echo "No launcher at $ide/eclipse." >&2; exit 2; }
[ ${#scripts[@]} -gt 0 ] || { echo "Give at least one script file." >&2; exit 2; }

here=$(cd "$(dirname "$0")" && pwd)

if [ -z "$workspace" ]; then
  workspace=$(mktemp -d -t mcp-test-ide-XXXXXX)
fi
mkdir -p "$workspace"

# Whether the server runs at all is an INSTANCE preference, so it belongs to the
# workspace: a workspace nobody has switched it on in comes up with nothing
# listening and no way left to ask why. Writing it before the first start is the
# only way out of that circle, since the tool that would switch it on is the one
# that cannot be reached.
settings="$workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
mkdir -p "$settings"
cat > "$settings/com.vogella.eclipse.mcp.server.prefs" <<PREFS
eclipse.preferences.version=1
enabled=true
port=$port
callTimeoutSeconds=60
PREFS

endpoint="$workspace/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json"
log="$workspace/.metadata/.log"
pid=

cleanup() {
  local status=$?
  # the whole process group, not the launcher: the native launcher spawns the
  # JVM as a child and may exit itself, so killing the recorded pid left a full
  # IDE running with nobody to notice. setsid below makes pid the group leader
  if [ -n "$pid" ]; then
    kill -TERM -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 -"$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 -"$pid" 2>/dev/null || true
  fi
  # and anything still holding this workspace, whatever its group
  for stray in $(ps -C java -o pid= 2>/dev/null); do
    if tr '\0' ' ' < "/proc/$stray/cmdline" 2>/dev/null | grep -qF -- "-data $workspace"; then
      kill -9 "$stray" 2>/dev/null || true
    fi
  done
  if [ "$keep" = "0" ]; then
    rm -rf "$workspace"
  else
    echo "workspace kept at $workspace"
    [ -f "$log" ] && echo "log at $log"
  fi
  exit $status
}
trap cleanup EXIT INT TERM

echo "starting $ide on $workspace, port $port"
setsid "$ide/eclipse" -data "$workspace" -nosplash > "$workspace/launch.log" 2>&1 &
pid=$!

deadline=$((SECONDS + timeout))
until [ -f "$endpoint" ] && curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$port/mcp"; do
  if ! kill -0 -"$pid" 2>/dev/null && ! kill -0 "$pid" 2>/dev/null; then
    echo "the IDE exited before the server came up; last lines of its log:" >&2
    tail -20 "$workspace/launch.log" >&2 || true
    [ -f "$log" ] && tail -20 "$log" >&2
    exit 1
  fi
  [ $SECONDS -lt $deadline ] || { echo "the server did not come up within ${timeout}s" >&2; exit 1; }
  sleep 2
done
echo "server up after $((timeout - (deadline - SECONDS)))s"

args=(--workspace "$workspace")
[ -n "$junit" ] && args+=(--junit "$junit")
"$here/mcp-script.py" "${args[@]}" "${scripts[@]}"
