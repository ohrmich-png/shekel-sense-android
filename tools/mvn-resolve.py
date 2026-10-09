#!/usr/bin/env python3
"""Minimal Maven resolver: BFS over POMs, downloads AAR (else JAR) via curl."""
import os, re, subprocess, sys, xml.etree.ElementTree as ET

CENTRAL = "https://repo.maven.apache.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"
OUT = os.path.expanduser("~/workspace/.m2repo")

ROOTS = [
    ("androidx.appcompat", "appcompat", "1.7.1"),
    ("androidx.coordinatorlayout", "coordinatorlayout", "1.3.0"),
    ("androidx.core", "core-splashscreen", "1.2.0"),
    ("androidx.core", "core", "1.17.0"),
    ("androidx.activity", "activity", "1.11.0"),
    ("androidx.fragment", "fragment", "1.8.9"),
    ("androidx.webkit", "webkit", "1.14.0"),
    ("org.apache.cordova", "framework", "14.0.1"),
]

def curl(url, out):
    r = subprocess.run(["curl", "-sL", "--max-time", "120", "-o", out, url],
                       capture_output=True)
    return r.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 0

def fetch_text(url):
    r = subprocess.run(["curl", "-sL", "--max-time", "60", url], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 and r.stdout else None

def artifact_path(g, a, v, ext):
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"

def get_pom(g, a, v):
    for base in (GOOGLE, CENTRAL):
        t = fetch_text(f"{base}/{artifact_path(g, a, v, 'pom')}")
        if t and "<project" in t:
            return t
    return None

def resolve_props(pom_text):
    props = {}
    try:
        root = ET.fromstring(pom_text)
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        for p in root.findall("m:properties", ns):
            for c in p:
                tag = re.sub(r"\{.*\}", "", c.tag)
                props[tag] = (c.text or "").strip()
    except Exception:
        pass
    return props

def sub_props(s, props):
    def rep(m):
        return props.get(m.group(1), m.group(0))
    prev = None
    while prev != s:
        prev = s
        s = re.sub(r"\$\{([^}]+)\}", rep, s)
    return s

def pom_deps(pom_text):
    """Return [(group, artifact, version)] for compile+runtime non-optional deps."""
    deps = []
    try:
        root = ET.fromstring(pom_text)
    except Exception:
        return deps
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    props = resolve_props(pom_text)
    # dependencyManagement for version defaults
    mgmt = {}
    for dm in root.findall("m:dependencyManagement/m:dependencies/m:dependency", ns):
        g = (dm.findtext("m:groupId", "", ns) or "").strip()
        a = (dm.findtext("m:artifactId", "", ns) or "").strip()
        v = (dm.findtext("m:version", "", ns) or "").strip()
        if g and a and v:
            mgmt[(g, a)] = sub_props(v, props)
    for d in root.findall("m:dependencies/m:dependency", ns):
        g = (d.findtext("m:groupId", "", ns) or "").strip()
        a = (d.findtext("m:artifactId", "", ns) or "").strip()
        v = (d.findtext("m:version", "", ns) or "").strip()
        scope = (d.findtext("m:scope", "", ns) or "compile").strip()
        opt = (d.findtext("m:optional", "", ns) or "").strip() == "true"
        typ = (d.findtext("m:type", "", ns) or "jar").strip()
        if not g or not a or opt or scope not in ("compile", "runtime"):
            continue
        v = sub_props(v, props) or mgmt.get((g, a), "")
        v = sub_props(v, props)
        if not v:
            continue
        # version range -> lower bound
        m = re.match(r"[\[\(]\s*([^,\s\)\]]+)", v)
        if m:
            v = m.group(1)
        deps.append((g, a, v, typ))
    return deps

def download(g, a, v):
    dest_dir = os.path.join(OUT, g.replace(".", "/"), a, v)
    os.makedirs(dest_dir, exist_ok=True)
    for ext in ("aar", "jar"):
        dest = os.path.join(dest_dir, f"{a}-{v}.{ext}")
        if os.path.exists(dest) and os.path.getsize(dest) > 1000:
            return dest, ext
        for base in (GOOGLE, CENTRAL):
            if curl(f"{base}/{artifact_path(g, a, v, ext)}", dest):
                # sanity: must be a zip
                with open(dest, "rb") as f:
                    if f.read(2) == b"PK":
                        print(f"  got {g}:{a}:{v} ({ext})")
                        return dest, ext
                os.remove(dest)
    print(f"  MISSING {g}:{a}:{v}")
    return None, None

def main():
    seen = {}
    queue = [(g, a, v) for g, a, v in ROOTS]
    artifacts = []  # (path, ext, group, artifact, version)
    while queue:
        g, a, v = queue.pop(0)
        key = (g, a)
        if key in seen:
            continue
        seen[key] = v
        print(f"resolving {g}:{a}:{v}")
        pom = get_pom(g, a, v)
        if pom:
            for dg, da, dv, typ in pom_deps(pom):
                if (dg, da) not in seen:
                    queue.append((dg, da, dv))
        path, ext = download(g, a, v)
        if path:
            artifacts.append((path, ext, g, a, v))
    with open(os.path.join(OUT, "artifacts.txt"), "w") as f:
        for path, ext, g, a, v in artifacts:
            f.write(f"{ext}|{g}|{a}|{v}|{path}\n")
    print(f"\n{len(artifacts)} artifacts resolved")

if __name__ == "__main__":
    main()
