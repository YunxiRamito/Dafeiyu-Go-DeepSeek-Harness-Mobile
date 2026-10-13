#!/usr/bin/env python3
"""Build reviewed, pinned Linux DSH trees from a fixed npm v3 PC lock.

No npm lifecycle scripts or downloaded programs are executed. The PC's
node_modules is never copied. All artifacts are obtained through the explicit
proxy and checked against the source lock's npm integrity before extraction.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import gzip
import hashlib
import io
import json
import pathlib
import re
import shutil
import tarfile
import urllib.error
import urllib.parse
import urllib.request

DSH_VERSION = "0.2.1-alpha.1"
NODE_VERSION = "24.19.0"
MIB = 1024 * 1024
ARTIFACT_LIMIT = 160 * MIB
TOTAL_LIMIT = 3 * 1024 * MIB
ENTRY_LIMIT = 60000
DEFAULT_PROXY = "http://127.0.0.1:7890"
ALLOWED_HOSTS = {"registry.npmjs.org", "registry.npmmirror.com", "registry.npmmirror.com:443"}
LOCAL_DSH_INTEGRITY = "sha512-DJ8pwG/XqzwDrYQTgJXuIga6ku153Z4vJI/B3MzNtudVCkDaOf4O2pUQh1ToroLHPPe4mxhhFeNJ7ttNculqMw=="


def serialized(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def checked_path(value: str):
    path = pathlib.PurePosixPath(value)
    if not value or "\\" in value or ":" in value or path.is_absolute() or any(x in (".", "..", "") for x in value.split("/")):
        raise ValueError(f"unsafe relative package path: {value!r}")
    return path


def package_name(path):
    return path.rsplit("node_modules/", 1)[1]


def supported(value, wanted):
    if not value:
        return True
    if "!" + wanted in value:
        return False
    positives = [x for x in value if not x.startswith("!")]
    return not positives or wanted in positives or "any" in positives


def compatible(record, architecture):
    return supported(record.get("os"), "linux") and supported(record.get("cpu"), architecture) and supported(record.get("libc"), "glibc")


def resolve(packages, owner, name):
    # npm's installed ancestry is authoritative, including duplicate versions.
    parent = owner
    while parent:
        candidate = parent + "/node_modules/" + name
        if candidate in packages:
            return candidate
        if "/node_modules/" not in parent:
            break
        parent = parent.rsplit("/node_modules/", 1)[0]
    candidate = "node_modules/" + name
    return candidate if candidate in packages else None


def closure(lock, architecture):
    packages = lock["packages"]
    root = "node_modules/@deepseek-ai/dsh"
    if lock.get("lockfileVersion") != 3 or packages[root].get("version") != DSH_VERSION:
        raise ValueError("source must be an npm v3 lock with the exact reviewed DSH version")
    selected = set()
    omitted = []
    edges = []
    pending = [root]
    while pending:
        path = pending.pop()
        if path in selected:
            continue
        checked_path(path)
        record = packages[path]
        if not compatible(record, architecture):
            raise ValueError(f"required package is incompatible with Linux/{architecture}: {path}")
        selected.add(path)
        dependencies = dict(record.get("dependencies", {}))
        optional = record.get("optionalDependencies", {})
        dependencies.update(optional)
        peers = record.get("peerDependencies", {})
        for name, requirement in {**dependencies, **peers}.items():
            is_optional = name in optional or (name in peers and record.get("peerDependenciesMeta", {}).get(name, {}).get("optional", False))
            target = resolve(packages, path, name)
            if target is None:
                if is_optional:
                    omitted.append({"from": path, "name": name, "reason": "optional dependency absent from fixed lock"})
                    continue
                raise ValueError(f"fixed lock cannot resolve {name}@{requirement} from {path}")
            if not compatible(packages[target], architecture):
                if is_optional:
                    omitted.append({"from": path, "name": name, "reason": f"platform gate linux/{architecture}/glibc"})
                    continue
                raise ValueError(f"nonoptional incompatible dependency: {path} -> {target}")
            edges.append({"from": path, "to": target, "requirement": requirement, "optional": is_optional})
            pending.append(target)
    return sorted(selected), edges, omitted


def artifact_descriptor(path, record):
    name = package_name(path)
    version = record.get("version")
    if not re.fullmatch(r"[A-Za-z0-9@._/-]+", name) or not re.fullmatch(r"[A-Za-z0-9.+_-]+", version or ""):
        raise ValueError("unsafe npm name or version")
    integrity = record.get("integrity")
    if path == "node_modules/@deepseek-ai/dsh":
        if integrity != LOCAL_DSH_INTEGRITY:
            raise ValueError("PC CLI lock does not match the independently checked registry artifact")
    if not integrity or not integrity.startswith(("sha512-", "sha256-", "sha1-")) or " " in integrity:
        raise ValueError(f"missing pinned npm integrity: {path}")
    resolved = record.get("resolved", "")
    # Canonical registry tarballs, never source-lock file: paths or mutable tags.
    url = f"https://registry.npmjs.org/{name}/-/{name.split('/')[-1]}-{version}.tgz"
    return {"path": path, "name": name, "version": version, "url": url, "integrity": integrity,
            "license": record.get("license", "UNKNOWN"), "sourceResolved": resolved if resolved.startswith("https:") else "local CLI artifact; matched registry integrity",
            "os": record.get("os"), "cpu": record.get("cpu"), "libc": record.get("libc"), "hasInstallScript": bool(record.get("hasInstallScript"))}


def checksum(file, algorithm="sha256"):
    result = hashlib.new(algorithm)
    with file.open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            result.update(block)
    return result.digest()


def valid_cache(file, integrity):
    algorithm, expected = integrity.split("-", 1)
    try:
        return file.is_file() and 0 < file.stat().st_size <= ARTIFACT_LIMIT and checksum(file, algorithm) == base64.b64decode(expected, validate=True)
    except (OSError, ValueError):
        return False


class Download:
    def __init__(self, proxy):
        self.proxy = proxy

    def request(self, url, headers=None):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.netloc not in ALLOWED_HOSTS or parsed.username or parsed.fragment:
            raise ValueError("unreviewed artifact origin")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": self.proxy, "https": self.proxy}))
        return opener.open(urllib.request.Request(url, headers={"User-Agent": "Dafeiyu-DSH-Bundle/1", "Accept-Encoding": "identity", **(headers or {})}), timeout=60)

    def obtain(self, descriptor, cache):
        name = hashlib.sha256(descriptor["integrity"].encode()).hexdigest() + ".tgz"
        destination = cache / name
        if valid_cache(destination, descriptor["integrity"]):
            return destination, {"cached": True, "threads": 0, "size": destination.stat().st_size}
        urls = [descriptor["url"].replace("registry.npmjs.org", "registry.npmmirror.com"), descriptor["url"]]
        last = None
        for url in urls:
            temporary = destination.with_suffix(".partial")
            try:
                evidence = self.fetch(url, temporary)
                if not valid_cache(temporary, descriptor["integrity"]):
                    raise ValueError(f"npm integrity mismatch: {descriptor['name']}@{descriptor['version']}")
                temporary.replace(destination)
                return destination, evidence
            except Exception as failure:
                last = failure
                temporary.unlink(missing_ok=True)
        raise RuntimeError(f"artifact unavailable for {descriptor['name']}@{descriptor['version']}: {last}")

    def fetch(self, url, target):
        # Probe Range explicitly. HTTP 200 never counts as a ranged download.
        with self.request(url, {"Range": "bytes=0-0"}) as probe:
            code = probe.status
            content_range = probe.headers.get("Content-Range", "")
            etag = probe.headers.get("ETag")
            match = re.fullmatch(r"bytes 0-0/(\d+)", content_range)
            if code != 206 or match is None:
                # Try official after a mirror without Range before single flow.
                if "registry.npmmirror.com" in url:
                    return self.fetch(url.replace("registry.npmmirror.com", "registry.npmjs.org"), target)
                return self.single(url, target, "both npm artifact lines lack verified byte ranges")
            size = int(match.group(1))
            if size <= 0 or size > ARTIFACT_LIMIT:
                raise ValueError("npm archive too large")
            if len(probe.read(2)) != 1:
                raise ValueError("byte range probe body must contain exactly one byte")
        if size <= MIB:
            return self.single(url, target, None)
        count = 4 if size <= 6 * MIB else 8
        parts = [target.with_suffix(f".part-{index}") for index in range(count)]

        def part(index):
            start = size * index // count
            end = size * (index + 1) // count - 1
            headers = {"Range": f"bytes={start}-{end}"}
            if etag and not etag.startswith("W/"):
                headers["If-Match"] = etag
            with self.request(url, headers) as source, parts[index].open("wb") as output:
                if source.status != 206 or source.headers.get("Content-Range") != f"bytes {start}-{end}/{size}" or (etag and source.headers.get("ETag") != etag):
                    raise ValueError("ranged response or entity changed")
                received = 0
                for block in iter(lambda: source.read(128 * 1024), b""):
                    received += len(block)
                    if received > end - start + 1:
                        raise ValueError("range overflow")
                    output.write(block)
                if received != end - start + 1:
                    raise ValueError("short range")

        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=count) as executor:
                list(executor.map(part, range(count)))
            with target.open("wb") as output:
                for file in parts:
                    with file.open("rb") as source:
                        shutil.copyfileobj(source, output, 128 * 1024)
            return {"cached": False, "url": url, "threads": count, "size": size, "etag": etag}
        finally:
            for file in parts:
                file.unlink(missing_ok=True)

    def single(self, url, target, fallback):
        size = 0
        with self.request(url) as source, target.open("wb") as output:
            if source.status != 200:
                raise ValueError("single artifact response must be HTTP 200")
            for block in iter(lambda: source.read(128 * 1024), b""):
                size += len(block)
                if size > ARTIFACT_LIMIT:
                    raise ValueError("npm archive exceeds maximum")
                output.write(block)
        return {"cached": False, "url": url, "threads": 1, "size": size, "fallback": fallback}


def scan_artifact(archive, descriptor, architecture=None):
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > ENTRY_LIMIT:
            raise ValueError("npm archive entry limit")
        files = {}
        modes = {}
        expanded = 0
        roots = {member.name.rstrip("/").split("/")[0] for member in members}
        expected_roots = {"package"}
        if descriptor["name"].startswith("@types/"):
            expected_roots.add(descriptor["name"].split("/")[-1])
        if len(roots) != 1 or not roots.issubset(expected_roots):
            raise ValueError(f"unexpected pinned npm root for {descriptor['name']}: {roots}")
        for member in members:
            raw_name = member.name.rstrip("/")
            if not raw_name or raw_name.startswith("/") or "\\" in raw_name or ":" in raw_name or ".." in raw_name.split("/"):
                raise ValueError("unsafe npm member path")
            # A few npm pack outputs include package/./dist. Canonicalize the
            # harmless dot spelling before duplicate checks and final packing.
            path = checked_path(pathlib.PurePosixPath(raw_name).as_posix())
            if len(path.parts) == 1:
                if not member.isdir():
                    raise ValueError("npm archive root is not a directory")
                continue
            relative = pathlib.PurePosixPath(*path.parts[1:]).as_posix()
            if member.isdir():
                continue
            if not member.isfile() or member.size < 0 or member.size > ARTIFACT_LIMIT:
                raise ValueError(f"npm archive has link, device or oversized entry: {member.name}")
            if architecture and descriptor["name"] == "node-pty":
                if relative.startswith("prebuilds/") and relative.split("/")[1] != f"linux-{architecture}":
                    continue
                if relative.startswith("third_party/conpty/") and relative.endswith((".dll", ".exe", ".pdb")):
                    continue
            if relative in files:
                previous = files[relative]
                if member.size != previous.size or member.mode != previous.mode:
                    raise ValueError("npm artifact conflicting duplicate entry")
                def member_digest(entry):
                    digest = hashlib.sha256()
                    with source.extractfile(entry) as stream:
                        for chunk in iter(lambda: stream.read(128 * 1024), b""):
                            digest.update(chunk)
                    return digest.digest()
                if member_digest(member) != member_digest(previous):
                    raise ValueError("npm artifact conflicting duplicate content")
                continue
            expanded += member.size
            if expanded > TOTAL_LIMIT:
                raise ValueError("npm artifact expansion limit")
            files[relative] = member
            # No setuid/setgid; reviewed helper modes added explicitly below.
            modes[relative] = 0o755 if member.mode & 0o111 else 0o644
        package = files.get("package.json")
        if package is None or package.size > MIB:
            raise ValueError("npm artifact package manifest missing")
        metadata = json.load(source.extractfile(package))
        if metadata.get("name") != descriptor["name"] or metadata.get("version") != descriptor["version"]:
            raise ValueError("npm artifact package identity mismatch")
        return metadata, files, modes, expanded


def build_bundle(output, arch, descriptors, archives, graph, omitted, source_digest):
    abi = "arm64-v8a" if arch == "arm64" else "x86_64"
    target = output / f"dsh-{DSH_VERSION}-linux-{arch}.tar.gz"
    index = []
    entry_count = 0
    total = 0
    native = []
    licenses = []
    audit = []
    for descriptor in descriptors:
        file, _ = archives[descriptor["integrity"]]
        metadata, entries, modes, expanded = scan_artifact(file, descriptor, arch)
        total += expanded
        entry_count += len(entries)
        if total > TOTAL_LIMIT or entry_count > ENTRY_LIMIT:
            raise ValueError("complete DSH bundle exceeds Android extractor limits")
        lifecycle = {key: value for key, value in metadata.get("scripts", {}).items() if key in {"preinstall", "install", "postinstall", "prepare"}}
        if lifecycle:
            audit.append({"name": descriptor["name"], "version": descriptor["version"], "scripts": lifecycle, "executed": False})
        for relative, member in entries.items():
            if relative.lower().endswith((".node", ".so", ".wasm")) or relative.endswith(("/rg", "spawn-helper")):
                native.append({"path": descriptor["path"] + "/" + relative, "bytes": member.size})
            if re.search(r"(^|/)(licen[cs]e|copying|notice)([.-]|$)", relative, re.I):
                licenses.append({"package": descriptor["name"], "path": descriptor["path"] + "/" + relative})
            if descriptor["name"] == "node-pty" and relative == f"prebuilds/linux-{arch}/spawn-helper":
                # dsh-subprocess-local/ensure-spawn-helper.mjs and node-pty's
                # post-install only chmod this helper. Preserve that reviewed
                # requirement without running either downloaded script.
                modes[relative] = 0o755
        index.append({**descriptor, "archiveSha256": checksum(file).hex(), "archiveBytes": file.stat().st_size,
                      "expandedBytes": expanded, "entryCount": len(entries),
                      "scriptsNotExecuted": lifecycle, "packageLicense": metadata.get("license", descriptor["license"])})
    lock = {"schema": 1, "dshVersion": DSH_VERSION, "nodeVersion": NODE_VERSION, "platform": "linux",
            "architecture": arch, "libc": "glibc", "sourceLockSha256": source_digest,
            "dependencyEdges": graph, "omittedOptional": omitted, "packages": index,
            "nativeAssets": native, "licenseFiles": licenses, "lifecycleAudit": audit,
            "sourceTreeCopied": False, "lifecycleScriptsExecuted": False}
    lock["platformFileFiltering"] = "node-pty: selected Linux ABI prebuilds; Windows ConPTY binaries omitted; license files retained"
    lock["npmArchiveNormalization"] = "canonical dot spelling; byte-identical duplicate entry with equal mode retained once; conflicting duplicates rejected"
    lock_bytes = serialized(lock)
    (output / f"dsh-{DSH_VERSION}-linux-{arch}.lock.json").write_bytes(lock_bytes)
    temp = target.with_suffix(".partial")
    expanded_total = total + len(lock_bytes)
    with temp.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=6, mtime=0) as gzip_out, tarfile.open(fileobj=gzip_out, mode="w", format=tarfile.PAX_FORMAT) as output_tar:
        for descriptor in descriptors:
            file, _ = archives[descriptor["integrity"]]
            metadata, entries, modes, _ = scan_artifact(file, descriptor, arch)
            with tarfile.open(file, "r:gz") as source:
                for relative, member in sorted(entries.items()):
                    info = tarfile.TarInfo(descriptor["path"] + "/" + relative)
                    info.size = member.size
                    info.mode = modes[relative]
                    if descriptor["name"] == "node-pty" and relative == f"prebuilds/linux-{arch}/spawn-helper":
                        info.mode = 0o755
                    info.mtime = 0
                    output_tar.addfile(info, source.extractfile(member))
        info = tarfile.TarInfo("dsh-bundle.lock.json")
        info.size = len(lock_bytes)
        info.mode = 0o644
        output_tar.addfile(info, io.BytesIO(lock_bytes))
    temp.replace(target)
    manifest = {"schema": 1, "runtimeId": f"dsh-{DSH_VERSION}-linux-{arch}", "dshVersion": DSH_VERSION,
                "nodeVersion": NODE_VERSION, "abi": abi, "archiveUrl": None,
                "archiveSha256": checksum(target).hex(), "archiveSize": target.stat().st_size,
                "maximumExpandedBytes": max(target.stat().st_size, expanded_total + 32 * MIB),
                "entryPoint": "node_modules/@deepseek-ai/dsh/lib/bin.js", "healthPath": "/",
                "requiredFiles": ["node_modules/@deepseek-ai/dsh/package.json", "node_modules/@deepseek-ai/dsh-web-app/lib/index.js",
                                  "node_modules/@deepseek-ai/dsh-client-connection/lib/index.js", "dsh-bundle.lock.json"],
                "dependencyLockSha256": hashlib.sha256(lock_bytes).hexdigest(), "license": "MIT"}
    (output / f"dsh-{DSH_VERSION}-linux-{arch}.manifest-unpublished.json").write_bytes(serialized(manifest))
    print(json.dumps({"bundle": str(target), "architecture": arch, "packages": len(descriptors), "entries": entry_count + 1,
                      "expandedBytes": expanded_total, "archiveBytes": manifest["archiveSize"], "sha256": manifest["archiveSha256"],
                      "published": False, "androidRuntimeVerified": False}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pc-lock", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--proxy", default=DEFAULT_PROXY)
    parser.add_argument("--architectures", nargs="+", choices=["arm64", "x64"], default=["arm64", "x64"])
    parser.add_argument("--plan-only", action="store_true")
    args = parser.parse_args()
    output = args.output.resolve()
    project_temp = pathlib.Path(__file__).resolve().parents[2] / "Temp_Android"
    if not output.is_relative_to(project_temp.resolve()):
        raise ValueError("bundle artifacts must remain under project Temp_Android")
    output.mkdir(parents=True, exist_ok=True)
    source_bytes = args.pc_lock.read_bytes()
    lock = json.loads(source_bytes)
    source_digest = hashlib.sha256(source_bytes).hexdigest()
    plans = []
    union = {}
    for arch in args.architectures:
        paths, edges, omitted = closure(lock, arch)
        descriptors = [artifact_descriptor(path, lock["packages"][path]) for path in paths]
        plans.append((arch, descriptors, edges, omitted))
        for descriptor in descriptors:
            union.setdefault(descriptor["integrity"], descriptor)
        print(json.dumps({"architecture": arch, "packages": len(paths), "omittedOptional": len(omitted),
                          "installScriptPackages": [v["name"] for v in descriptors if v["hasInstallScript"]]}), flush=True)
    (output / "build-plan.json").write_bytes(serialized({"sourceLockSha256": source_digest, "dshVersion": DSH_VERSION,
        "architectures": [{"architecture": arch, "packages": descriptors, "edges": edges, "omitted": omitted}
                          for arch, descriptors, edges, omitted in plans]}))
    if args.plan_only:
        return
    cache = output / "npm-cache"
    cache.mkdir(exist_ok=True)
    downloader = Download(args.proxy)
    archives = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(downloader.obtain, descriptor, cache): key for key, descriptor in union.items()}
        for count, future in enumerate(concurrent.futures.as_completed(futures), 1):
            archives[futures[future]] = future.result()
            if count % 25 == 0 or count == len(futures):
                print(f"integrity-verified npm artifacts: {count}/{len(futures)}", flush=True)
    for arch, descriptors, edges, omitted in plans:
        build_bundle(output, arch, descriptors, archives, edges, omitted, source_digest)
    (output / "download-evidence.json").write_bytes(serialized({key: evidence for key, (_, evidence) in archives.items()}))


if __name__ == "__main__":
    main()
