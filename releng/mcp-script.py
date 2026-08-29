#!/usr/bin/env python3
"""Runs an eclipse_run_script file against a running Eclipse MCP server.

Discovers the endpoint and the bearer token, runs the script, prints a
transcript, exits non-zero when a step failed, and optionally writes JUnit XML
so a build can report the steps as test cases.

Usage:
  releng/mcp-script.py [options] <script.json> [<script.json> ...]

Options:
  --url URL          Endpoint, default from the discovery file or the workspace.
  --token TOKEN      Bearer token, default from ~/.eclipse/.../token.
  --workspace DIR    Read the discovery file of this workspace.
  --junit FILE       Write JUnit XML here.
  --timeout SECONDS  HTTP timeout per call, default 120.
  --quiet            Only print failures and the summary.
"""

import argparse
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

QUALIFIER = "com.vogella.eclipse.mcp.server"
DEFAULT_URL = "http://127.0.0.1:8642/mcp"


def token_path():
    return pathlib.Path.home() / ".eclipse" / QUALIFIER / "token"


def discovery_path(workspace):
    return pathlib.Path(workspace) / ".metadata" / ".plugins" / QUALIFIER / "endpoint.json"


def discover(args):
    """The endpoint and token, from the arguments, a workspace, or the defaults."""
    url, token = args.url, args.token
    if args.workspace:
        found = discovery_path(args.workspace)
        if not found.exists():
            sys.exit("No discovery file at %s; is that IDE running with the server on?" % found)
        data = json.loads(found.read_text())
        url = url or data.get("url")
        token = token or data.get("token")
    if not token:
        path = token_path()
        if not path.exists():
            sys.exit("No token at %s and none given; pass --token." % path)
        token = path.read_text().strip()
    return url or DEFAULT_URL, token


class Client:
    """Just enough MCP to call one tool: initialize, notify, call."""

    def __init__(self, url, token, timeout):
        self.url = url
        self.token = token
        self.timeout = timeout
        self.session = None
        self.next_id = 0

    def _post(self, body):
        headers = {
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            self.session = response.headers.get("Mcp-Session-Id", self.session)
            text = response.read().decode()
            streamed = response.headers.get("Content-Type", "").startswith("text/event-stream")
        if not text.strip():
            return None
        if streamed:
            for line in text.splitlines():
                if line.startswith("data:"):
                    return json.loads(line[5:])
            return None
        return json.loads(text)

    def _id(self):
        self.next_id += 1
        return self.next_id

    def initialize(self):
        self._post({"jsonrpc": "2.0", "id": self._id(), "method": "initialize",
                    "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                               "clientInfo": {"name": "mcp-script", "version": "1"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, tool, arguments):
        answer = self._post({"jsonrpc": "2.0", "id": self._id(), "method": "tools/call",
                             "params": {"name": tool, "arguments": arguments}})
        result = (answer or {}).get("result", answer or {})
        text = "".join(c.get("text", "") for c in result.get("content", []))
        return text, bool(result.get("isError"))


def run(client, path, quiet, verbose=False):
    """Runs one script file and returns (name, seconds, steps, failure or None)."""
    script = json.loads(pathlib.Path(path).read_text())
    started = time.time()
    text, error = client.call("eclipse_run_script", script)
    seconds = time.time() - started
    if error:
        return path, seconds, [], "the server refused the script: " + text.strip()
    try:
        answer = json.loads(text)
    except json.JSONDecodeError:
        return path, seconds, [], "the answer was not JSON: " + text[:400]
    steps = answer.get("steps", [])
    if not quiet or answer.get("failed"):
        print("%s  %s" % (path, "PASS" if not answer.get("failed") else "FAIL"))
        for step in steps:
            mark = "ok  " if step.get("ok") else ("--  " if not step.get("ran") else "FAIL")
            print("   %s %-28s %sms" % (mark, str(step.get("label"))[:28], step.get("millis")))
            for bad in step.get("expectationsFailed") or []:
                print("        %s: expected %s, found %s" % (bad.get("path"), bad.get("expected"), bad.get("found")))
            if verbose and step.get("ok"):
                shown = step.get("answer")
                text = json.dumps(shown, indent=1) if isinstance(shown, (dict, list)) else str(shown)
                print("        answer: " + text[:600].replace("\n", "\n        "))
            if step.get("ran") and not step.get("ok"):
                # the expectation says what did not hold; the answer says why, and
                # without it a CI failure is a puzzle rather than a report
                # not named answer: that is the script's answer, and shadowing it
                # here made the summary read this step's and report no failures
                detail = step.get("answer")
                rendered = json.dumps(detail, indent=1) if isinstance(detail, (dict, list)) else str(detail)
                print("        answer: " + rendered[:600].replace("\n", "\n        "))
    failure = None
    if answer.get("failed"):
        failure = "%d of %d steps failed" % (answer.get("failed"), answer.get("total"))
    return path, seconds, steps, failure


def junit(results, path):
    suite = ET.Element("testsuite", name="eclipse-mcp-script",
                       tests=str(sum(max(1, len(s)) for _, _, s, _ in results)),
                       failures=str(sum(1 for _, _, _, f in results if f)),
                       time="%.3f" % sum(t for _, t, _, _ in results))
    for name, seconds, steps, failure in results:
        base = pathlib.Path(name).stem
        if not steps:
            case = ET.SubElement(suite, "testcase", classname=base, name=base, time="%.3f" % seconds)
            if failure:
                ET.SubElement(case, "failure", message=failure).text = failure
            continue
        for step in steps:
            case = ET.SubElement(suite, "testcase", classname=base,
                                 name=str(step.get("label")), time="%.3f" % ((step.get("millis") or 0) / 1000.0))
            if not step.get("ran"):
                ET.SubElement(case, "skipped", message="an earlier step failed")
            elif not step.get("ok"):
                detail = json.dumps(step.get("expectationsFailed") or step.get("answer"), indent=1)[:4000]
                ET.SubElement(case, "failure", message="step failed").text = detail
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description="Run eclipse_run_script files against a running IDE.")
    parser.add_argument("scripts", nargs="+")
    parser.add_argument("--url")
    parser.add_argument("--token")
    parser.add_argument("--workspace")
    parser.add_argument("--junit")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--verbose", action="store_true",
                        help="Print every step's answer, which is how a script is debugged.")
    args = parser.parse_args()

    url, token = discover(args)
    client = Client(url, token, args.timeout)
    try:
        client.initialize()
    except (urllib.error.URLError, OSError) as e:
        sys.exit("Could not reach %s: %s" % (url, e))

    results = [run(client, path, args.quiet, args.verbose) for path in args.scripts]
    if args.junit:
        junit(results, args.junit)
    failed = [name for name, _, _, failure in results if failure]
    print("%d script(s), %d failed" % (len(results), len(failed)))
    for name, _, _, failure in results:
        if failure:
            print("  %s: %s" % (name, failure))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
