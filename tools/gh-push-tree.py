#!/usr/bin/env python3
"""Push a local directory as a new commit on main via the Git Data API."""
import base64, json, os, sys, urllib.request

sys.path.insert(0, "/opt/hatch/skills/skill-creator/bin")
from dynamic_credentials import add_surrogate_to_request, read_json_response

API = "https://api.github.com"
ALLOWED = ["api.github.com"]
REPO = "ohrmich-png/shekel-sense-android"
ROOT = os.path.expanduser("~/workspace/shekel-sense-android")
SKIP_DIRS = {"node_modules", ".git", ".gradle", "build"}
SKIP_FILES = {".DS_Store", "debug.keystore", "app-debug.apk"}

def api(method, path, data=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(API + path, data=body, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body:
        req.add_header("Content-Type", "application/json")
    add_surrogate_to_request(req, "custom.github", allowed_hosts=ALLOWED)
    return read_json_response(urllib.request.urlopen(req))

def main():
    entries = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn in SKIP_FILES:
                continue
            full = os.path.join(dirpath, fn)
            entries.append((os.path.relpath(full, ROOT), full))
    entries.sort()
    print(f"{len(entries)} files")
    tree = []
    for i, (rel, full) in enumerate(entries):
        with open(full, "rb") as f:
            raw = f.read()
        blob = api("POST", f"/repos/{REPO}/git/blobs",
                   {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
        tree.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    t = api("POST", f"/repos/{REPO}/git/trees", {"tree": tree})
    base = api("GET", f"/repos/{REPO}/git/ref/heads/main")["object"]["sha"]
    c = api("POST", f"/repos/{REPO}/git/commits",
            {"message": "Android companion: colors.xml, MainActivity plugin registration, build tooling",
             "tree": t["sha"], "parents": [base]})
    r = api("PATCH", f"/repos/{REPO}/git/refs/heads/main", {"sha": c["sha"]})
    print("commit:", c["sha"])
    print("ref:", r["ref"], "->", r["object"]["sha"])

if __name__ == "__main__":
    main()
