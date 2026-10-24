#!/usr/bin/env python3
"""merge-manifest.py <app manifest> <out manifest> <library AndroidManifest.xml...>

A small stand-in for Gradle's manifest merger: copies the libraries' permissions, features, queries and
application components (GeckoView's content-process services, androidx.startup's provider) into the app's
manifest, fills in ${applicationId}, and lets Android extract the native libraries.
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
ET.register_namespace("android", A[1:-1])


def strip_tools(e):
    for k in [k for k in e.attrib if k.startswith(TOOLS)]:
        del e.attrib[k]
    for c in e:
        strip_tools(c)


def main():
    app_path, out_path, libs = sys.argv[1], sys.argv[2], sys.argv[3:]
    tree = ET.parse(app_path)
    root = tree.getroot()
    pkg = root.get("package")
    app = root.find("application")
    queries = root.find("queries")
    have = {(e.tag, e.get(A + "name")) for e in list(root) + list(app)}
    for path in libs:
        lib = ET.fromstring(open(path, encoding="utf-8").read().replace("${applicationId}", pkg))
        strip_tools(lib)
        for e in lib:
            if e.tag in ("uses-permission", "uses-feature") and (e.tag, e.get(A + "name")) not in have:
                if e.get(A + "name") is None and e.get(A + "glEsVersion") is None:
                    continue
                root.insert(0, e)
                have.add((e.tag, e.get(A + "name")))
            elif e.tag == "queries":
                if queries is None:
                    queries = ET.SubElement(root, "queries")
                queries.extend(list(e))
        lapp = lib.find("application")
        if lapp is None:
            continue
        for k, v in lapp.attrib.items():
            if k == A + "zygotePreloadName" and app.get(k) is None:
                app.set(k, v)
        for c in lapp:
            key = (c.tag, c.get(A + "name"))
            if key in have:                                   # same provider from several libraries: merge its meta-data
                existing = next(x for x in app if (x.tag, x.get(A + "name")) == key)
                names = {m.get(A + "name") for m in existing}
                existing.extend(m for m in c if m.get(A + "name") not in names)
                continue
            app.append(c)
            have.add(key)
    app.set(A + "extractNativeLibs", "true")
    tree.write(out_path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
