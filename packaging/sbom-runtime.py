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


# Code other libraries carry inside their own jar, renamed, which Maven doesn't know about. Keyed by
# the carrier's purl and version: when a bump changes that version, this script stops until the table
# is checked again (the same versions are in THIRD-PARTY-NOTICES and the Debian copyright file).
EMBEDDED = {
    ("pkg:maven/org.tomlj/tomlj", "2.2.0"): [
        ("pkg:maven/org.antlr/antlr4-runtime@4.13.2", "org.antlr", "antlr4-runtime", "4.13.2",
         "BSD-3-Clause", "https://github.com/antlr/antlr4"),
    ],
    ("pkg:maven/com.fasterxml.jackson.core/jackson-core", "2.23.0"): [
        ("pkg:maven/ch.randelshofer/fastdoubleparser@2.0.1", "ch.randelshofer", "fastdoubleparser", "2.0.1",
         "MIT", "https://github.com/wrandelshofer/FastDoubleParser"),
        ("pkg:generic/schubfach", None, "schubfach", None,
         "MIT", "https://github.com/c4f7fcce9cb06515/Schubfach"),
    ],
}


def embed(components):
    """Adds the embedded code as nested components of the library that carries it."""
    for (purl, version), inner in EMBEDDED.items():
        carriers = [c for c in components if c.get("purl", "").split("@")[0] == purl]
        if not carriers:
            sys.exit(f"{purl} isn't in the SBOM any more: update EMBEDDED in {__file__}")
        for c in carriers:
            if c.get("version") != version:
                sys.exit(f"{purl} is {c.get('version')} now, not {version}: check what it embeds "
                         f"and update EMBEDDED in {__file__}")
            nested = c.setdefault("components", [])
            for ref, group, name, v, license_id, vcs in inner:
                entry = {"type": "library", "bom-ref": ref, "name": name,
                         "licenses": [{"license": {"id": license_id}}], "purl": ref,
                         "externalReferences": [{"type": "vcs", "url": vcs}],
                         "description": "embedded in " + c.get("name", purl) + " under a renamed package"}
                if group:
                    entry["group"] = group
                if v:
                    entry["version"] = v
                if all(n.get("bom-ref") != ref for n in nested):
                    nested.append(entry)


def main(path, home, url):
    with open(path, encoding="utf-8") as f:
        bom = json.load(f)
    components = bom.setdefault("components", [])
    embed(components)
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
