#!/usr/bin/env python3
"""Downloads the launcher's libraries into launcher/gecko/ for build.sh: GeckoView (armeabi-v7a, the doorbell's
browser) and the Shizuku client (Clean up), with their runtime dependencies.

No Gradle: walks the Maven POMs itself, keeps the newest version of each library, and skips what the
doorbell page doesn't need (FIDO security keys via Play services, the media3 HLS player).
"""
import os, re, sys, urllib.request
import xml.etree.ElementTree as ET

GECKO = ("org.mozilla.geckoview", "geckoview-armeabi-v7a", "158.0.20261021001945")
SHIZUKU = [("dev.rikka.shizuku", "api", "13.1.5"), ("dev.rikka.shizuku", "provider", "13.1.5")]   # Clean up's adb-level rights
REPOS = ["https://maven.mozilla.org/maven2/", "https://dl.google.com/dl/android/maven2/", "https://repo1.maven.org/maven2/"]
SKIP = re.compile(r"^(com\.google\.android\.gms|androidx\.media3|org\.checkerframework|com\.google\.errorprone|org\.jspecify)")
SKIP_ARTIFACTS = {"guava", "failureaccess"}      # androidx only needs Guava's ListenableFuture interface (listenablefuture)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gecko")
NS = "{http://maven.apache.org/POM/4.0.0}"


def get(path):
    for r in REPOS:
        try:
            with urllib.request.urlopen(r + path, timeout=60) as f:
                return f.read()
        except Exception:
            continue
    return None


def vkey(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]


def pom(g, a, v):
    data = get(f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
    if data is None:
        sys.exit(f"pom not found: {g}:{a}:{v}")
    root = ET.fromstring(data)
    props = {}
    p = root.find(f"{NS}properties")
    if p is not None:
        for e in p:
            props[e.tag.replace(NS, "")] = (e.text or "").strip()
    props["project.version"] = v
    deps = []
    for d in root.findall(f"{NS}dependencies/{NS}dependency"):
        t = lambda k: (d.findtext(f"{NS}{k}") or "").strip()
        scope, ver = t("scope") or "compile", t("version")
        if scope in ("test", "provided", "system") or t("optional") == "true":
            continue
        ver = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), ver).strip("[]")
        if not ver or "$" in ver:
            continue
        deps.append((t("groupId"), t("artifactId"), ver.split(",")[0]))
    packaging = (root.findtext(f"{NS}packaging") or "jar").strip()
    return deps, packaging


def main():
    chosen, queue = {}, [GECKO] + SHIZUKU
    while queue:
        g, a, v = queue.pop()
        if SKIP.match(g) or a in SKIP_ARTIFACTS:
            continue
        key = (g, a)
        if key in chosen and vkey(chosen[key][0]) >= vkey(v):
            continue
        deps, packaging = pom(g, a, v)
        chosen[key] = (v, packaging)
        queue.extend(deps)
    os.makedirs(OUT, exist_ok=True)
    for (g, a), (v, packaging) in sorted(chosen.items()):
        ext = "aar" if packaging == "aar" else "jar"
        name = f"{a}-{v}.{ext}"
        dest = os.path.join(OUT, name)
        if not os.path.exists(dest):
            data = get(f"{g.replace('.', '/')}/{a}/{v}/{name}")
            if data is None and ext == "jar":          # some POMs say jar for an aar
                ext, name = "aar", f"{a}-{v}.aar"
                dest = os.path.join(OUT, name)
                data = get(f"{g.replace('.', '/')}/{a}/{v}/{name}")
            if data is None:
                sys.exit(f"artifact not found: {g}:{a}:{v}")
            with open(dest, "wb") as f:
                f.write(data)
        print(f"{g}:{a}:{v}  {ext}  {os.path.getsize(dest) // 1024} KB")


if __name__ == "__main__":
    main()
