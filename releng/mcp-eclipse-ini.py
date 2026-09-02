#!/usr/bin/env python3
"""Chooses a free MCP server port for an Eclipse instance before it starts.

Writes a plug-in customization file that switches the server on and names the
port, to be handed to Eclipse as -pluginCustomization <file>, and records the
port in a registry shared by every instance. Any number of IDEs can then be
started without anybody editing a port by hand.

Usage:
  releng/mcp-eclipse-ini.py [options] (--workspace DIR | --name NAME | --ini FILE)

  eclipse -data ~/ws/swt \\
    -pluginCustomization "$(releng/mcp-eclipse-ini.py --workspace ~/ws/swt)"

Options:
  --workspace DIR    The workspace the instance will open. Names the ini file
                     and lets the script recognise its own server on the port.
  --name NAME        Name the ini file after this instead of the workspace.
  --ini FILE         Use exactly this ini file.
  --dir DIR          Folder for the ini files and the registry. Default
                     $ECLIPSE_MCP_INSTANCES, else
                     ~/.eclipse/com.vogella.eclipse.mcp.server/instances
  --call-timeout N   Also set the tool call timeout, in seconds.
  --print WHAT       What goes to stdout: ini (default), port, or args, which
                     is the complete "-pluginCustomization <file>".
  --list             Clean up, show the registry and exit.
  --cleanup          Clean up and exit without allocating anything.
  --quiet            No notes on stderr.

An existing ini keeps its port as long as that port is free, or is held by the
MCP server of the same workspace, so an instance is reachable at the same
address across restarts. A port that another program has taken is replaced and
the ini rewritten; lines other than the server's own are preserved.

The registry is <dir>/ports.json. On every run an entry is dropped when the ini
it names is gone or no longer names that port AND nothing is listening on the
port; a port still in use stays reserved until it is free. Deleting an ini is
how a port is given back.

Ports are taken from 8642 upwards, the plug-in's default, and stay below 8700
when they can. Beyond that the range up to 8999 is used, minus the ports other
software is known to sit on. Only when every one of those is spoken for is a
port picked by the operating system.

The file sets the DEFAULT scope. A workspace that has set the port or the
switch on its own preference page keeps that value; the script warns when it
sees one.
"""

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import socket
import sys
import time
import urllib.error
import urllib.request

QUALIFIER = "com.vogella.eclipse.mcp.server"
KEY_PORT = QUALIFIER + "/port"
KEY_ENABLED = QUALIFIER + "/enabled"
KEY_TIMEOUT = QUALIFIER + "/callTimeoutSeconds"
REGISTRY = "ports.json"
LOCK = "ports.lock"

DEFAULT_PORT = 8642
# the plug-in's own default and the ports right after it, which is where a
# person reading a URL expects an Eclipse to be
PRIMARY = range(DEFAULT_PORT, 8700)
FALLBACK = range(8700, 9000)
# ports inside those ranges that other software is known to use. 8743 is the
# default of mcp-test-ide.sh and must stay out of the way of a working IDE
AVOID = {
    8649, 8651, 8652,  # ganglia
    8686,  # glassfish jmx
    8743,  # mcp-test-ide.sh
    8761,  # eureka
    8765,  # common dev default
    8787,  # rstudio
    8834,  # nessus
    8880, 8888,  # alternative http, jupyter
    8883,  # mqtt over tls
    8983,  # solr
}

LOCK_STALE_SECONDS = 60
LOCK_WAIT_SECONDS = 10


def note(quiet, text):
    if not quiet:
        print(text, file=sys.stderr)


def default_dir():
    env = os.environ.get("ECLIPSE_MCP_INSTANCES")
    if env:
        return pathlib.Path(env).expanduser()
    return pathlib.Path.home() / ".eclipse" / QUALIFIER / "instances"


def ini_for(args, folder):
    """The ini file this run is about, from --ini, --name or --workspace."""
    if args.ini:
        return pathlib.Path(args.ini).expanduser().resolve()
    if args.name:
        return folder / (args.name + ".ini")
    workspace = pathlib.Path(args.workspace).expanduser().resolve()
    # the basename alone collides for ~/a/ws and ~/b/ws, and the full path is
    # unwieldy as a file name; a short hash tells them apart
    digest = hashlib.sha1(str(workspace).encode()).hexdigest()[:8]
    return folder / ("%s-%s.ini" % (workspace.name or "workspace", digest))


def is_free(port):
    """Whether the server could bind the port right now."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        # Jetty binds with SO_REUSEADDR, so a port in TIME_WAIT is free for it
        # and must count as free here. Not on Windows, where the same option
        # allows binding over a socket somebody is still listening on
        if os.name != "nt":
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def served_workspace(port):
    """The workspace an MCP server on the port says it serves, or None.

    The server answers a request without a token with a 401 whose text names
    the workspace, which is enough to tell our own instance from a stranger.
    """
    try:
        urllib.request.urlopen("http://127.0.0.1:%d/mcp" % port, timeout=1)
    except urllib.error.HTTPError as error:
        if error.code != 401:
            return None
        text = error.read().decode(errors="replace")
        marker = "serving the workspace "
        start = text.find(marker)
        if start < 0:
            return None
        rest = text[start + len(marker):]
        end = rest.rfind(".")
        return rest[:end] if end > 0 else rest
    except (urllib.error.URLError, OSError, ValueError):
        return None
    return None


def same_path(a, b):
    if not a or not b:
        return False
    try:
        return pathlib.Path(a).resolve() == pathlib.Path(b).resolve()
    except OSError:
        return a == b


# ---- the ini file -----------------------------------------------------------

def read_ini(path):
    """The lines of the ini and the port it names, or None."""
    if not path.exists():
        return [], None
    lines = path.read_text(encoding="utf-8").splitlines()
    port = None
    for line in lines:
        key, sep, value = line.partition("=")
        if sep and key.strip() == KEY_PORT:
            try:
                port = int(value.strip())
            except ValueError:
                port = None
    return lines, port


def write_ini(path, lines, port, call_timeout, workspace):
    """Rewrites the server's own keys and keeps every other line as it was."""
    ours = {KEY_PORT, KEY_ENABLED, KEY_TIMEOUT}
    kept = [line for line in lines
            if not (line.partition("=")[1] and line.partition("=")[0].strip() in ours)
            and not line.startswith("# mcp-eclipse-ini")]
    header = ["# mcp-eclipse-ini: written %s" % datetime.datetime.now().isoformat(timespec="seconds")]
    if workspace:
        header.append("# mcp-eclipse-ini: for the workspace %s" % workspace)
    header.append("# mcp-eclipse-ini: delete this file to give the port back")
    body = [KEY_ENABLED + "=true", KEY_PORT + "=%d" % port]
    if call_timeout:
        body.append(KEY_TIMEOUT + "=%d" % call_timeout)
    kept = [line for line in kept if line.strip()]
    atomic_write(path, "\n".join(header + body + kept) + "\n")


def atomic_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp%d" % os.getpid())
    temp.write_text(text, encoding="utf-8")
    os.replace(temp, path)


def ini_names(path, port):
    """Whether the ini still exists and still names this port."""
    try:
        return read_ini(pathlib.Path(path))[1] == port
    except OSError:
        return False


# ---- the registry -----------------------------------------------------------

class Registry:
    """<dir>/ports.json: which port belongs to which ini, under a lock file."""

    def __init__(self, folder):
        self.folder = folder
        self.path = folder / REGISTRY
        self.lock = folder / LOCK
        self.ports = {}

    def __enter__(self):
        self.folder.mkdir(parents=True, exist_ok=True)
        deadline = time.time() + LOCK_WAIT_SECONDS
        while True:
            try:
                fd = os.open(self.lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(fd, str(os.getpid()).encode())
                os.close(fd)
                break
            except FileExistsError:
                try:
                    age = time.time() - self.lock.stat().st_mtime
                except OSError:
                    continue
                if age > LOCK_STALE_SECONDS:
                    # a launcher that died holding it must not block every later one
                    self.lock.unlink(missing_ok=True)
                    continue
                if time.time() > deadline:
                    sys.exit("Another mcp-eclipse-ini.py holds %s; remove it if none is running." % self.lock)
                time.sleep(0.1)
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                self.ports = {int(k): v for k, v in data.get("ports", {}).items()}
            except (ValueError, OSError):
                self.ports = {}
        return self

    def __exit__(self, *exc):
        atomic_write(self.path, json.dumps({"version": 1, "ports": {str(k): self.ports[k] for k in sorted(self.ports)}},
                                           indent=2, sort_keys=True) + "\n")
        self.lock.unlink(missing_ok=True)

    def cleanup(self):
        """Drops what is neither referenced by an ini nor in use. Returns (removed, held)."""
        removed, held = [], []
        for port in sorted(self.ports):
            entry = self.ports[port]
            if ini_names(entry.get("ini", ""), port):
                entry.pop("unreferencedSince", None)
                continue
            if not is_free(port):
                # not ours to give back while something listens on it
                entry.setdefault("unreferencedSince", now())
                held.append(port)
                continue
            del self.ports[port]
            removed.append(port)
        return removed, held

    def owner(self, port):
        return self.ports.get(port)

    def claim(self, port, ini, workspace, name):
        previous = self.ports.get(port)
        since = previous["allocated"] if previous and previous.get("ini") == str(ini) else now()
        entry = {"ini": str(ini), "allocated": since}
        if workspace:
            entry["workspace"] = str(workspace)
        if name:
            entry["name"] = name
        self.ports[port] = entry

    def release_ini(self, ini):
        for port in [p for p, e in self.ports.items() if e.get("ini") == str(ini)]:
            del self.ports[port]

    def allocate(self):
        """The first free, unreserved, uncommon port, preferring the primary range."""
        for candidates in (PRIMARY, FALLBACK):
            for port in candidates:
                if port in AVOID or port in self.ports:
                    continue
                if is_free(port):
                    return port, False
        # every preferred port is taken: let the system choose rather than fail
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1], True


def now():
    return datetime.datetime.now().isoformat(timespec="seconds")


# ---- the workspace's own preferences -----------------------------------------

def warn_about_instance_scope(workspace, quiet):
    """An instance scoped value beats the ini and would silently win."""
    if not workspace:
        return
    prefs = (pathlib.Path(workspace) / ".metadata" / ".plugins" / "org.eclipse.core.runtime" / ".settings"
             / (QUALIFIER + ".prefs"))
    if not prefs.exists():
        return
    try:
        text = prefs.read_text(encoding="utf-8")
    except OSError:
        return
    keys = [line.split("=", 1)[0].strip() for line in text.splitlines()
            if line.split("=", 1)[0].strip() in ("port", "enabled")]
    if keys:
        note(quiet, "Note: %s sets %s in the workspace itself, which overrides the ini. "
                    "Remove those lines, or clear them on the preference page, for the ini to take effect."
             % (prefs, ", ".join(keys)))


# ---- main --------------------------------------------------------------------

def parse():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--workspace")
    parser.add_argument("--name")
    parser.add_argument("--ini")
    parser.add_argument("--dir")
    parser.add_argument("--call-timeout", type=int)
    parser.add_argument("--print", dest="show", choices=("ini", "port", "args"), default="ini")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--cleanup", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("-h", "--help", action="store_true")
    args = parser.parse_args()
    if args.help:
        print(__doc__.strip())
        sys.exit(0)
    if not (args.list or args.cleanup) and not (args.workspace or args.name or args.ini):
        sys.exit("Give --workspace DIR, --name NAME or --ini FILE. See --help.")
    return args


def main():
    args = parse()
    quiet = args.quiet
    folder = pathlib.Path(args.dir).expanduser() if args.dir else default_dir()
    workspace = pathlib.Path(args.workspace).expanduser().resolve() if args.workspace else None

    with Registry(folder) as registry:
        removed, held = registry.cleanup()
        if removed:
            note(quiet, "Gave back port(s) %s: no ini names them and nothing listens." % ", ".join(map(str, removed)))
        if held:
            note(quiet, "Port(s) %s stay reserved: no ini names them but something is listening." % ", ".join(map(str, held)))

        if args.list or args.cleanup:
            if args.list:
                if not registry.ports:
                    print("No ports recorded in %s" % registry.path)
                for port in sorted(registry.ports):
                    entry = registry.ports[port]
                    state = "listening" if not is_free(port) else "free"
                    print("%d  %-9s  %s%s" % (port, state, entry.get("ini", "?"),
                                              "  " + entry["workspace"] if entry.get("workspace") else ""))
            return

        ini = ini_for(args, folder)
        lines, current = read_ini(ini)
        port = None
        if current is not None:
            owner = registry.owner(current)
            if owner and owner.get("ini") != str(ini):
                note(quiet, "Port %d in %s is recorded for %s; choosing another." % (current, ini, owner["ini"]))
            elif is_free(current):
                port = current
            elif same_path(served_workspace(current), workspace):
                port = current
                note(quiet, "Port %d is held by the MCP server of this very workspace, so it is kept." % current)
            else:
                note(quiet, "Port %d in %s is taken by something else; choosing another." % (current, ini))

        allocated = False
        if port is None:
            registry.release_ini(ini)
            port, outside = registry.allocate()
            allocated = True
            if outside:
                note(quiet, "Every port between %d and %d is reserved or in use; the system chose %d."
                     % (PRIMARY.start, FALLBACK.stop - 1, port))

        if allocated or current != port or not ini.exists():
            write_ini(ini, lines, port, args.call_timeout, workspace)
            note(quiet, "%s %s with port %d." % ("Wrote" if allocated else "Updated", ini, port))
        elif args.call_timeout and (KEY_TIMEOUT + "=%d" % args.call_timeout) not in lines:
            write_ini(ini, lines, port, args.call_timeout, workspace)
        registry.claim(port, ini, workspace, args.name)

    warn_about_instance_scope(workspace, quiet)

    if args.show == "port":
        print(port)
    elif args.show == "args":
        print("-pluginCustomization %s" % ini)
    else:
        print(ini)


if __name__ == "__main__":
    main()
