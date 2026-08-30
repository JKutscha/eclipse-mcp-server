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
#   --shared-config  Use the installation's own configuration area, so the test
#                    IDE runs whatever is substituted into it. Off by default.
#
# The installation is REUSED, not copied. Only the workspace, the configuration
# area and the p2 data area are fresh: the configuration is 3 MB of the 553 MB,
# and it is where bundles.info lives, which is the file a substitution edits. So
# the test IDE shares every bundle jar and still runs the shipped ones, whatever
# anybody has substituted into the installation somebody works in.
#
# The p2 data area has to be its own, and that is not an optimisation. Sharing
# the installation's one made p2 treat the installation as a shared install and
# rewrite its SDKProfile into a surrogate pointing at this temporary workspace,
# which broke provisioning in the real IDE as soon as the workspace was deleted.

set -euo pipefail

ide=${ECLIPSE_TEST_IDE:-}
port=8743
workspace=
keep=0
junit=
timeout=180
isolate=1
scripts=()

while [ $# -gt 0 ]; do
  case "$1" in
    --ide) ide=$2; shift 2;;
    --port) port=$2; shift 2;;
    --workspace) workspace=$2; keep=1; shift 2;;
    --keep) keep=1; shift;;
    --shared-config) isolate=0; shift;;
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

launch_args=()
if [ "$isolate" = "1" ]; then
  # bundles.info lives in the configuration area and its bundle paths are
  # relative to the installation, so a configuration of our own shares every jar
  # and still decides for itself which ones to load
  config="$workspace/configuration"
  mkdir -p "$config/org.eclipse.equinox.simpleconfigurator"
  cp "$ide/configuration/config.ini" "$config/config.ini"
  cp "$ide/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" \
     "$config/org.eclipse.equinox.simpleconfigurator/bundles.info"
  python3 - "$ide" "$config" <<'PY'
import pathlib, re, sys

install, config = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])

# p2's data area is written relative to the configuration directory, so a
# configuration of our own has to say where it is. It gets one of its OWN, and
# that is the whole point rather than a detail: pinning it at the installation
# is what this script used to do, and it corrupted the installation it was
# reusing. A configuration area outside the installation plus a profile
# registry inside it is exactly the shared-install shape p2 is built for, so p2
# built a surrogate profile for the temporary workspace and wrote the surrogate
# markers into the real SDKProfile. A surrogate profile does not own the base
# units, it references them from the shared install, so once the temporary
# workspace was deleted the installation's own profile pointed at nothing and
# every later `p2.director` run failed with "Missing requirement:
# org.eclipse.core.runtime ... could not be found".
# The cost of a private p2 area is that p2's picture of the installation is
# empty inside the test IDE, so eclipse_get_installation and the provisioning
# tools have nothing to report there. That is the right trade: those tools must
# never run in a throwaway IDE anyway, and the alternative was writing to the
# profile of the installation somebody works in.
p2 = config / "p2"
p2.mkdir(parents=True, exist_ok=True)
ini = config / "config.ini"
ini.write_text(re.sub(r"(?m)^eclipse\.p2\.data\.area=.*$",
                      "eclipse.p2.data.area=" + str(p2) + "/", ini.read_text()))

# and any line pointing at a substituted jar goes back to the shipped one, so a
# test never silently measures somebody else's patched bundle
info = config / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"
out, restored = [], []
for line in info.read_text().splitlines():
    parts = line.split(",")
    if len(parts) >= 3 and "mcp-substituted" in parts[2]:
        shipped = sorted((install / "plugins").glob(parts[0] + "_*.jar"))
        if shipped:
            jar = shipped[-1]
            version = jar.stem.split("_", 1)[1]
            parts[1], parts[2] = version, "plugins/" + jar.name
            restored.append(parts[0])
            line = ",".join(parts)
    out.append(line)
info.write_text("\n".join(out) + "\n")
if restored:
    print("restored to the shipped jar: " + ", ".join(restored))
PY
  launch_args+=(-configuration "$config")
fi

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
setsid "$ide/eclipse" -data "$workspace" "${launch_args[@]}" -nosplash > "$workspace/launch.log" 2>&1 &
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
