#!/usr/bin/env python3
"""Adds the Java runtime compiled into the jkit binary to the CycloneDX SBOM.

The cyclonedx-maven-plugin lists only Maven dependencies. A GraalVM native image also contains
the OpenJDK class library and GraalVM's Substrate VM, and the third-party code they carry; see
the "Java runtime" part of THIRD-PARTY-NOTICES.

Usage: sbom-runtime.py <sbom.json> <graalvm home> <graalvm download url>

The versions come from the GraalVM the binary was built with, so they can't drift from the pin in
release.yml: the JDK version from its release file, the GraalVM version from its download's
release tag (graal-25.5.5.0.3). This SBOM is made on linux-x64; the macOS x64 binary is built
with Liberica NIK of the same version, the same GraalVM Community code.
"""
import json
import os
import re
import sys


def versions(home, url):
    jdk = None
    with open(os.path.join(home, "release"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("JAVA_VERSION="):
                jdk = line.split("=", 1)[1].strip().strip('"')
    m = re.search(r"/graal-([0-9][0-9.]*)/", url)
    if not jdk or not m:
        sys.exit(f"can't tell the JDK ({jdk}) or GraalVM version from {home} and {url}")
    return m.group(1), jdk


def runtime(graalvm, jdk):
    return [
        ("org.graalvm", "graalvm-community-native-image", graalvm,
         "GPL-2.0-only WITH Classpath-exception-2.0", "https://github.com/oracle/graal"),
        ("org.openjdk", "openjdk-class-library", jdk,
         "GPL-2.0-only WITH Classpath-exception-2.0 AND Apache-2.0 AND BSD-2-Clause AND CC0-1.0 AND ICU AND MPL-2.0"
         " AND Unicode-3.0 AND W3C AND Zlib AND LicenseRef-CUP AND LicenseRef-Cryptix",
         "https://github.com/openjdk/jdk" + jdk.split(".")[0] + "u"),
    ]


def main(path, home, url):
    with open(path, encoding="utf-8") as f:
        bom = json.load(f)
    components = bom.setdefault("components", [])
    known = {c.get("bom-ref") for c in components}
    top = bom["metadata"]["component"]["bom-ref"]
    added = []
    for group, name, version, license_expr, vcs in runtime(*versions(home, url)):
        ref = f"pkg:generic/{group}/{name}@{version}"
        if ref in known:
            continue
        components.append({
            "type": "framework",
            "bom-ref": ref,
            "group": group,
            "name": name,
            "version": version,
            "licenses": [{"expression": license_expr}],
            "purl": ref,
            "externalReferences": [{"type": "vcs", "url": vcs}],
            "description": "compiled into the jkit native binary; its bundled third-party code is "
                           "listed in THIRD-PARTY-NOTICES",
        })
        added.append(ref)
    deps = bom.setdefault("dependencies", [])
    for d in deps:
        if d.get("ref") == top:
            d.setdefault("dependsOn", []).extend(added)
            break
    else:
        deps.append({"ref": top, "dependsOn": added})
    deps.extend({"ref": ref, "dependsOn": []} for ref in added)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(bom, f, indent=2)
        f.write("\n")
    print(f"added {len(added)} runtime components to {path}")

if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
