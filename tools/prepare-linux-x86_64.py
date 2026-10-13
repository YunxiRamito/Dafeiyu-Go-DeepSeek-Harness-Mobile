#!/usr/bin/env python3
"""Prepare verified native x86_64 PRoot and a fixed Ubuntu guest archive.

Build data only: no upstream lifecycle script, executable or shell is run.
All artifacts and prepared files remain in the project's G: Temp_Android.
"""
from __future__ import annotations
import argparse
import base64
import bz2
import concurrent.futures
import functools
import gzip
import hashlib
import io
import json
import lzma
import pathlib
import re
import shutil
import subprocess
import tarfile
import urllib.request
from importlib.util import module_from_spec, spec_from_file_location

TEMP = pathlib.Path(__file__).resolve().parents[2] / "Temp_Android"
MIB = 1024 * 1024
RELEASE = "ubuntu24.04.3-node24.19.0-x86_64-20261012"
ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
ROOTFS_SHA = "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9"
NODE_URL = "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-x64.tar.gz"
NODE_SHA = "f625d97cd707df4ff96254916fbc5ff014f09c09effe5a1e0ca8f6d41a8789d4"
HOSTS = {"cdimage.ubuntu.com", "archive.ubuntu.com", "security.ubuntu.com", "nodejs.org",
         "packages.termux.dev", "registry.npmjs.org", "registry.npmmirror.com"}


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(128 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def records(text):
    for block in text.strip().split("\n\n"):
        fields = {}
        key = None
        for line in block.splitlines():
            if line.startswith(" ") and key is not None:
                fields[key] += "\n" + line[1:]
            elif ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value
        if fields:
            yield fields


def compare_version(left, right):
    def split(value):
        epoch, _, rest = value.partition(":")
        if not rest:
            epoch, rest = "0", epoch
        upstream, sep, revision = rest.rpartition("-")
        return int(epoch), upstream if sep else rest, revision if sep else "0"
    def compare(a, b):
        while a or b:
            def order(c):
                if c == "~": return -1
                if not c or c.isdigit(): return 0
                return ord(c) if c.isalpha() else ord(c) + 256
            while (a and not a[0].isdigit()) or (b and not b[0].isdigit()):
                delta = order(a[0] if a else "") - order(b[0] if b else "")
                if delta: return 1 if delta > 0 else -1
                if a: a = a[1:]
                if b: b = b[1:]
            aa = re.match(r"\d*", a)[0]; bb = re.match(r"\d*", b)[0]
            a = a[len(aa):]; b = b[len(bb):]
            aa = aa.lstrip("0"); bb = bb.lstrip("0")
            if len(aa) != len(bb): return 1 if len(aa) > len(bb) else -1
            if aa != bb: return 1 if aa > bb else -1
        return 0
    le, lu, lr = split(left); re_, ru, rr = split(right)
    if le != re_: return 1 if le > re_ else -1
    return compare(lu, ru) or compare(lr, rr)


def ubuntu_closure(research):
    packages = {}
    indices = []
    for suffix in ("noble-main", "noble-updates-main", "noble-security-main"):
        file = research / f"ubuntu-{suffix}-Packages.gz"
        indices.append({"file": file.name, "sha256": sha(file)})
        text = gzip.decompress(file.read_bytes()).decode("utf-8")
        for record in records(text):
            if record.get("Architecture") not in ("amd64", "all"):
                continue
            name = record["Package"]
            if name not in packages or compare_version(record["Version"], packages[name]["Version"]) > 0:
                packages[name] = record
    requested = ["git", "python3", "python3.12", "libpython3.12-stdlib", "ca-certificates", "bubblewrap"]
    providers = {}
    for name, record in packages.items():
        for spelling in record.get("Provides", "").split(","):
            match = re.fullmatch(r"\s*([a-z0-9+.-]+)(?:\s*\(=\s*([^()]+)\))?\s*", spelling)
            if match:
                virtual, version = match.groups()
                providers.setdefault(virtual, []).append((name, version))
    chosen = {}
    pending = list(requested)
    while pending:
        name = pending.pop()
        if name in chosen: continue
        if name not in packages: raise ValueError(f"Ubuntu package absent from fixed indices: {name}")
        record = packages[name]; chosen[name] = record
        for raw_group in (record.get("Pre-Depends", "") + "," + record.get("Depends", "")).split(","):
            if not raw_group.strip(): continue
            alternatives = []
            for raw in raw_group.split("|"):
                match = re.fullmatch(r"\s*([a-z0-9+.-]+)(?::any|:native|:amd64)?\s*(?:\((>=|<=|=|>>|<<)\s*([^()]+)\))?\s*", raw)
                if not match: raise ValueError(f"Unsupported dependency: {raw}")
                dep, relation, version = match.groups()
                if dep in packages:
                    compare = compare_version(packages[dep]["Version"], version) if version else 0
                    satisfies = relation is None or {">=": compare >= 0, "<=": compare <= 0, "=": compare == 0, ">>": compare > 0, "<<": compare < 0}[relation]
                    if satisfies: alternatives.append(dep)
                else:
                    for provider, provided_version in providers.get(dep, []):
                        compare = compare_version(provided_version, version) if provided_version and version else 0
                        satisfies = relation is None or (provided_version is not None and {">=": compare >= 0, "<=": compare <= 0, "=": compare == 0, ">>": compare > 0, "<<": compare < 0}[relation])
                        if satisfies: alternatives.append(provider)
            if not alternatives: raise ValueError(f"No fixed dependency satisfies {name}: {raw_group}")
            pending.append(next((dep for dep in alternatives if dep in chosen), alternatives[0]))
    selected = [{k: record.get(k) for k in ("Package", "Version", "Source", "Homepage", "Architecture", "Filename", "Size", "SHA256", "Depends", "Pre-Depends")}
                for _, record in sorted(chosen.items())]
    return selected, indices


def ar_members(data):
    if not data.startswith(b"!<arch>\n"): raise ValueError("DEB ar header missing")
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        if len(header) != 60 or header[58:] != b"`\n": raise ValueError("DEB ar member invalid")
        name = header[:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        offset += 60
        if size < 0 or offset + size > len(data): raise ValueError("DEB member bounds invalid")
        yield name, data[offset:offset + size]
        offset += size + size % 2


def deb_data(deb, target):
    for name, data in ar_members(deb.read_bytes()):
        if name.startswith("data.tar."):
            destination = target / (deb.stem + "." + name)
            destination.write_bytes(data)
            if name.endswith(".zst"):
                # The OS-supplied libarchive reads Zstandard. Its archive-copy
                # mode writes a plain tar without extracting or executing any
                # DEB content, so Linux names never touch the Windows filesystem.
                plain = destination.with_suffix(".plain.tar")
                partial = plain.with_suffix(".partial")
                subprocess.run(["tar", "-c", "--format=pax", "-f", str(partial), "@" + str(destination)],
                               check=True, timeout=120, capture_output=True)
                if partial.stat().st_size > 2 * 1024 * MIB:
                    partial.unlink(missing_ok=True)
                    raise ValueError("DEB data expansion exceeds preparation bound")
                partial.replace(plain)
                return plain
            return destination
    raise ValueError("DEB data archive missing")


def prepare_native(research, output, downloader):
    packages = json.loads((research / "native-x86_64-packages.json").read_text(encoding="utf-8"))
    native = TEMP / "runtime/jniLibs/x86_64"
    native.mkdir(parents=True, exist_ok=True)
    mapping = {
        "proot": {"bin/proot": "liblinux_proot.so", "libexec/proot/loader": "liblinux_prootloader.so", "libexec/proot/loader32": "liblinux_prootloader32.so"},
        "libtalloc": {"lib/libtalloc.so.2.5.0": "liblinux_talloc.so"},
        "libandroid-shmem": {"lib/libandroid-shmem.so": "liblinux_androidshmem.so"}}
    elf = []
    for package in packages:
        url = "https://packages.termux.dev/apt/termux-main/" + package["Filename"]
        deb = receive(output / "downloads", pathlib.PurePosixPath(package["Filename"]).name, url, package["SHA256"], downloader)
        data = deb_data(deb, output / "data-archives")
        with tarfile.open(data) as archive:
            for suffix, packaged in mapping[package["Package"]].items():
                member = archive.getmember("./data/data/com.termux/files/usr/" + suffix)
                if not member.isfile() or member.size > 2 * MIB: raise ValueError("native ELF member invalid")
                payload = archive.extractfile(member).read()
                if payload[:4] != b"\x7fELF": raise ValueError("native binary is not ELF")
                machine = int.from_bytes(payload[18:20], "little")
                if machine not in (3, 62): raise ValueError("native ELF architecture mismatch")
                destination = native / packaged
                if destination.exists() and destination.read_bytes() != payload:
                    backup = TEMP / "backup-0.1.25-dsh/x86_64-native"; backup.mkdir(parents=True, exist_ok=True)
                    (backup / (packaged + "." + sha(destination))).write_bytes(destination.read_bytes())
                destination.write_bytes(payload)
                elf.append({"packaged": packaged, "member": member.name, "sha256": hashlib.sha256(payload).hexdigest(),
                            "package": package["Package"], "elfMachine": machine, "bytes": len(payload)})
    (output / "native-x86_64-lock.json").write_bytes(json_bytes({"schema": 1, "architecture": "x86_64", "nativePackages": packages,
        "nativeFiles": elf, "sourceArchives": "same fixed PRoot5.1.107.96/talloc2.5.0/shmem0.7 complete sources already packaged", "rebuilt": False}))
    return elf


def receive(cache, filename, url, checksum, downloader):
    file = cache / filename
    if file.is_file() and sha(file) == checksum: return file
    partial = file.with_suffix(file.suffix + ".partial")
    downloader.fetch(url, partial)
    if sha(partial) != checksum:
        partial.unlink(missing_ok=True); raise ValueError(f"checksum failed: {filename}")
    partial.replace(file)
    return file


def safe_name(value):
    if value.startswith("/") or "\\" in value or ".." in value.split("/") or "\0" in value:
        raise ValueError("unsafe guest tar path")
    normalized = pathlib.PurePosixPath(value).as_posix()
    return "" if normalized == "." else normalized


def build_guest(output, inputs, package_lock, source_indices, native):
    sources = []
    tree = {}
    def add_archive(path, transform=lambda value: value):
        # Final output is path-sorted. Seek compressed input repeatedly would
        # decompress large rootfs archives thousands of times. Stream each
        # verified input to a plain G: tar once, without filesystem extraction.
        decoders = {".gz": gzip.open, ".xz": lzma.open, ".bz2": bz2.open}
        decoder = decoders.get(path.suffix)
        if decoder is not None:
            plain = output / "data-archives" / (path.name + ".decoded.tar")
            with decoder(path, "rb") as compressed, plain.open("wb") as decoded:
                shutil.copyfileobj(compressed, decoded, length=128 * 1024)
            if plain.stat().st_size > 2 * 1024 * MIB:
                plain.unlink(missing_ok=True)
                raise ValueError("preparation tar exceeds size limit")
            path = plain
        source = tarfile.open(path); sources.append(source)
        for member in source:
            name = safe_name(member.name.rstrip("/"))
            if not name: continue
            name = transform(name)
            if not name: continue
            if name in {"dev", "proc", "sys"} or name.startswith(("dev/", "proc/", "sys/")):
                continue  # Real Android device/proc/sys mounts supplied by PRoot.
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError(f"unsupported guest archive entry: {name}")
            if member.size > 160 * MIB: raise ValueError("guest file too large")
            clone = tarfile.TarInfo(name); clone.size = member.size; clone.mode = member.mode & 0o777
            clone.type = member.type; clone.linkname = member.linkname; clone.mtime = 0
            if clone.issym() or clone.islnk():
                if "\0" in clone.linkname or "\\" in clone.linkname: raise ValueError("unsafe guest link")
            tree[name] = (clone, source, member)

    add_archive(inputs[0]["path"])
    # Rootfs is usrmerged. Canonicalize overlay paths rather than writing
    # through its /bin, /sbin, /lib, /lib64 alias symlinks during extraction.
    aliases = {name: item[0].linkname.lstrip("/") for name, item in tree.items() if name in {"bin", "sbin", "lib", "lib64"} and item[0].issym()}
    def usrmerge(name):
        head, sep, rest = name.partition("/")
        return aliases[head] + ("/" + rest if sep else "") if head in aliases else name
    for artifact in inputs[1:]:
        kind = artifact["kind"]
        if kind == "ubuntu-deb": add_archive(artifact["data"], usrmerge)
        elif kind == "node":
            add_archive(artifact["path"], lambda name: "usr/local/" + name.split("/", 1)[1] if "/" in name else "usr/local")
        elif kind == "pnpm":
            add_archive(artifact["path"], lambda name: "usr/local/lib/dsha-pnpm/" + name.split("/", 1)[1] if "/" in name else "usr/local/lib/dsha-pnpm")
    lock = {"schema": 1, "runtimeId": RELEASE, "architecture": "x86_64", "ubuntuVersion": "24.04.3", "nodeVersion": "24.19.0",
            "sourceIndices": source_indices, "ubuntuPackages": package_lock, "nativeFiles": native,
            "sources": [{key: value for key, value in artifact.items() if key not in {"path", "data"}} for artifact in inputs],
            "upstreamScriptsExecuted": False, "deviceNodes": "omitted; PRoot binds actual Android /dev /proc /sys", "licenseFilesRetained": True,
            "installation": "data overlays, not apt/dpkg script execution; module and health probes required before publication"}
    lock_bytes = json_bytes(lock)
    (output / "linux-x86_64.lock.json").write_bytes(lock_bytes)
    additions = {"etc/dafeiyu-runtime-lock.json": lock_bytes,
                 "root/dsh-bin/pnpm": b'#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs "$@"\n',
                 "usr/local/bin/npm": b'#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/npm-cli.js "$@"\n',
                 "usr/local/bin/npx": b'#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/npx-cli.js "$@"\n'}
    # Do not publish inaccurate dpkg installed state for packages whose
    # configuration scripts were deliberately not run.
    additions["etc/dafeiyu-data-overlay-packages.json"] = json_bytes({"configurationScriptsExecuted": False, "packages": package_lock})
    # Static certificate bundle from the reviewed ca-certificates data archive.
    certificate_members = sorted((name, item) for name, item in tree.items() if name.startswith("usr/share/ca-certificates/") and name.endswith(".crt") and item[0].isfile())
    additions["etc/ssl/certs/ca-certificates.crt"] = b"\n".join(item[1].extractfile(item[2]).read() for _, item in certificate_members)
    for name, payload in additions.items():
        info = tarfile.TarInfo(name); info.size = len(payload); info.mode = 0o755 if name in {"root/dsh-bin/pnpm", "usr/local/bin/npm", "usr/local/bin/npx"} else 0o644
        tree[name] = (info, None, payload)
    # Deferred rootfs links may not redirect later entries. Empty canonical
    # parents are created automatically by the Android safe extractor.
    parents = set()
    for name in tree:
        parents.update(str(parent) for parent in pathlib.PurePosixPath(name).parents)
    for name, (info, _, _) in tree.items():
        if info.issym() and name in parents:
            raise ValueError(f"guest link owns descendant entries: {name}")
    target = output / f"{RELEASE}.tar.gz"
    partial = target.with_suffix(".partial")
    expanded = sum(info.size for info, _, _ in tree.values() if info.isfile())
    if len(tree) > 60000 or expanded > 2 * 1024 * MIB: raise ValueError("guest exceeds Android extractor limits")
    try:
        with partial.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=6, mtime=0) as zipped, tarfile.open(fileobj=zipped, mode="w", format=tarfile.PAX_FORMAT) as archive:
            for name, (info, source, original) in sorted(tree.items()):
                payload = io.BytesIO(original) if source is None else source.extractfile(original) if info.isfile() else None
                archive.addfile(info, payload)
        partial.replace(target)
    finally:
        for source in sources: source.close()
    manifest = {"schema": 1, "runtimeId": RELEASE, "abi": "x86_64", "archiveUrl": None,
                "archiveSha256": sha(target), "archiveSize": target.stat().st_size, "maximumExpandedBytes": expanded + 32 * MIB,
                "lockSha256": hashlib.sha256(lock_bytes).hexdigest(), "versions": {"ubuntu": "24.04", "node": "24.19.0", "git": "2.43.0", "python": "3.12", "pnpm": "10.34.5"}}
    (output / "linux-x86_64.manifest-unpublished.json").write_bytes(json_bytes(manifest))
    print(json.dumps({"guest": str(target), "entries": len(tree), "expandedBytes": expanded, "size": manifest["archiveSize"], "sha256": manifest["archiveSha256"], "androidVerified": False}), flush=True)


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--research", type=pathlib.Path, default=TEMP / "research/linux-x86_64-20261012")
    args.add_argument("--output", type=pathlib.Path, default=TEMP / "linux-x86_64-bundles")
    args.add_argument("--proxy", default="http://127.0.0.1:7890")
    args.add_argument("--plan-only", action="store_true")
    config = args.parse_args()
    output = config.output.resolve()
    if not output.is_relative_to(TEMP.resolve()): raise ValueError("prepared artifacts must stay under project Temp_Android")
    for folder in (output, output / "downloads", output / "data-archives"): folder.mkdir(parents=True, exist_ok=True)
    spec = spec_from_file_location("dsh_bundle_download", pathlib.Path(__file__).with_name("build-dsh-bundle.py"))
    module = module_from_spec(spec); spec.loader.exec_module(module)
    module.ALLOWED_HOSTS = HOSTS
    downloader = module.Download(config.proxy)
    packages, indices = ubuntu_closure(config.research)
    plan = {"requested": ["git", "python3", "python3.12", "libpython3.12-stdlib", "ca-certificates", "bubblewrap"], "ubuntuPackages": packages,
            "indexDigests": indices, "rootfs": {"url": ROOTFS_URL, "sha256": ROOTFS_SHA}, "node": {"url": NODE_URL, "sha256": NODE_SHA}}
    (output / "build-plan.json").write_bytes(json_bytes(plan))
    print(json.dumps({"ubuntuPackages": len(packages), "packageVersions": {p["Package"]: p["Version"] for p in packages if p["Package"] in {"git", "python3.12", "bubblewrap", "libc6"}}}), flush=True)
    if config.plan_only: return
    native = prepare_native(config.research, output, downloader)
    rootfs = receive(output / "downloads", "ubuntu-base-24.04.3-base-amd64.tar.gz", ROOTFS_URL, ROOTFS_SHA, downloader)
    node = receive(output / "downloads", "node-v24.19.0-linux-x64.tar.gz", NODE_URL, NODE_SHA, downloader)
    inputs = [{"kind": "rootfs", "path": rootfs, "url": ROOTFS_URL, "sha256": ROOTFS_SHA},
              {"kind": "node", "path": node, "url": NODE_URL, "sha256": NODE_SHA}]
    def package_input(package):
        url = "https://archive.ubuntu.com/ubuntu/" + package["Filename"]
        deb = receive(output / "downloads", pathlib.PurePosixPath(package["Filename"]).name, url, package["SHA256"], downloader)
        return {"kind": "ubuntu-deb", "name": package["Package"], "version": package["Version"], "path": deb,
                "data": deb_data(deb, output / "data-archives"), "url": url, "sha256": package["SHA256"]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        inputs[1:1] = list(executor.map(package_input, packages))
    pnpm = json.loads((config.research / "pnpm10.34.5.json").read_text(encoding="utf-8"))
    pnpm_file, evidence = downloader.obtain({"name": "pnpm", "version": "10.34.5", "url": pnpm["dist"]["tarball"], "integrity": pnpm["dist"]["integrity"]}, output / "downloads")
    inputs.append({"kind": "pnpm", "path": pnpm_file, "url": pnpm["dist"]["tarball"], "integrity": pnpm["dist"]["integrity"], "sha256": sha(pnpm_file)})
    build_guest(output, inputs, packages, indices, native)


if __name__ == "__main__": main()
