#!/usr/bin/env python3
"""Tests for the release manifest: apkinfo.py on built APKs, and GET /v1/releases/** in ingest.py.

The APKs are built here byte by byte (a zip, a binary manifest, an APK Signing Block holding a
fake certificate), so the tests need no Android SDK and no real key.
"""
import hashlib
import io
import os
import struct
import zipfile

import apkinfo
from test_ingest import Harness

NO_INDEX = 0xFFFFFFFF
LAUNCHER_CERT = b"launcher release certificate"
SUITE_CERT = b"rav4-apps suite certificate"


def _axml(package, version_code, version_name):
    """A binary AndroidManifest.xml holding only <manifest package versionCode versionName>."""
    strings = ["manifest", "package", "versionCode", "versionName", package, version_name]
    body = b""
    offsets = []
    for s in strings:
        offsets.append(len(body))
        body += struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\0\0"
    body += b"\0" * (-len(body) % 4)
    header = 28 + 4 * len(strings)
    pool = struct.pack("<HHIIIIII", 0x0001, 28, header + len(body), len(strings), 0, 0, header, 0)
    pool += b"".join(struct.pack("<I", o) for o in offsets) + body

    attrs = [
        (1, 4, 0x03, 4),                 # package="..."     (string)
        (2, NO_INDEX, 0x10, version_code),  # versionCode=n  (int)
        (3, 5, 0x03, 5),                 # versionName="..."
    ]
    ext = struct.pack("<IIHHHHHH", NO_INDEX, 0, 20, 20, len(attrs), 0, 0, 0)
    ext += b"".join(struct.pack("<IIIHBBI", NO_INDEX, n, raw, 8, 0, t, v) for n, raw, t, v in attrs)
    element = struct.pack("<HHIII", 0x0102, 16, 16 + len(ext), 1, NO_INDEX) + ext

    chunks = pool + element
    return struct.pack("<HHI", 0x0003, 8, 8 + len(chunks)) + chunks


def _prefixed(b):
    return struct.pack("<I", len(b)) + b


def _signing_block(cert, scheme=apkinfo.SIG_V2):
    signed = _prefixed(b"") + _prefixed(_prefixed(cert)) + _prefixed(b"")
    signer = _prefixed(signed) + _prefixed(b"") + _prefixed(b"")
    value = _prefixed(_prefixed(signer))
    pair = struct.pack("<QI", 4 + len(value), scheme) + value
    size = len(pair) + 8 + 16
    return struct.pack("<Q", size) + pair + struct.pack("<Q", size) + apkinfo.SIG_BLOCK_MAGIC


def build_apk(package, version_code, version_name="1.0", cert=SUITE_CERT, scheme=apkinfo.SIG_V2):
    """APK bytes: a zip with the manifest and a payload, and a signing block before its directory."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", _axml(package, version_code, version_name))
        z.writestr("classes.dex", f"{package} {version_code}".encode())
    raw = buf.getvalue()
    if cert is None:
        return raw

    eocd = raw.rfind(apkinfo.EOCD_MAGIC)
    cd = struct.unpack_from("<I", raw, eocd + 16)[0]
    block = _signing_block(cert, scheme)
    tail = bytearray(raw[cd:])
    struct.pack_into("<I", tail, eocd - cd + 16, cd + len(block))
    return raw[:cd] + block + bytes(tail)


def digest(b):
    return hashlib.sha256(b).hexdigest()


class ApkInfoTest(Harness):

    def put(self, path, blob):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(blob)
        return path

    def test_reads_package_version_and_v2_cert(self):
        p = self.put(os.path.join(self.tmp, "a.apk"), build_apk("com.ripostelabs.clock", 7, "1.7"))
        info = apkinfo.read(p)
        self.assertEqual("com.ripostelabs.clock", info["package"])
        self.assertEqual(7, info["version_code"])
        self.assertEqual("1.7", info["version_name"])
        self.assertEqual(digest(SUITE_CERT), info["cert_sha256"])

    def test_v3_cert_is_read(self):
        p = self.put(os.path.join(self.tmp, "b.apk"),
                     build_apk("com.x", 1, cert=LAUNCHER_CERT, scheme=apkinfo.SIG_V3))
        self.assertEqual(digest(LAUNCHER_CERT), apkinfo.read(p)["cert_sha256"])

    def test_unsigned_has_no_cert(self):
        p = self.put(os.path.join(self.tmp, "c.apk"), build_apk("com.x", 1, cert=None))
        self.assertIsNone(apkinfo.read(p)["cert_sha256"])

    def test_not_an_apk_is_refused(self):
        p = self.put(os.path.join(self.tmp, "d.apk"), b"hello")
        with self.assertRaises(apkinfo.ApkError):
            apkinfo.read(p)


class ReleasesTest(Harness):
    """GET /v1/releases/manifest.json and the files it names, from a temp release tree."""

    def setUp(self):
        super().setUp()
        self.cfg.releases_root = os.path.join(self.tmp, "carlauncher")
        self.cfg.suite_root = os.path.join(self.tmp, "rav4-apps")
        os.makedirs(self.cfg.releases_root)
        os.makedirs(self.cfg.suite_root)

    def put(self, root, name, blob):
        with open(os.path.join(root, name), "wb") as f:
            f.write(blob)
        return blob

    def manifest(self):
        status, _, body = self.call("GET", "/v1/releases/manifest.json")
        self.assertEqual(200, status)
        return body

    def test_manifest_lists_newest_launcher_carservice_and_every_suite_app(self):
        root, suite = self.cfg.releases_root, self.cfg.suite_root
        self.put(root, "carlauncher-0.7-vc968.apk", build_apk("com.ripostelabs.carlauncher", 968, "0.7", LAUNCHER_CERT))
        newest = self.put(root, "carlauncher-0.7-vc975.apk",
                          build_apk("com.ripostelabs.carlauncher", 975, "0.7", LAUNCHER_CERT))
        self.put(root, "carservice-0.7-vc975.apk", build_apk("com.ripostelabs.car", 975, "0.3", LAUNCHER_CERT))
        clock = self.put(suite, "com.ripostelabs.clock.apk", build_apk("com.ripostelabs.clock", 3))
        self.put(suite, "com.ripostelabs.notes.apk", build_apk("com.ripostelabs.notes", 1))
        self.put(root, "index.html", b"<html>")

        m = self.manifest()
        self.assertEqual("riposte-releases/1", m["schema"])
        by_pkg = {a["package"]: a for a in m["apps"]}
        self.assertEqual({"com.ripostelabs.carlauncher", "com.ripostelabs.car",
                          "com.ripostelabs.clock", "com.ripostelabs.notes"}, set(by_pkg))

        launcher = by_pkg["com.ripostelabs.carlauncher"]
        self.assertEqual("launcher", launcher["role"])
        self.assertEqual(975, launcher["version_code"])
        self.assertEqual(digest(newest), launcher["sha256"])
        self.assertEqual(len(newest), launcher["size"])
        self.assertEqual(digest(LAUNCHER_CERT), launcher["cert_sha256"])
        self.assertEqual("carlauncher-0.7-vc975.apk", launcher["path"])

        self.assertEqual("carservice", by_pkg["com.ripostelabs.car"]["role"])
        c = by_pkg["com.ripostelabs.clock"]
        self.assertEqual(("suite", 3, "suite/com.ripostelabs.clock.apk", digest(clock)),
                         (c["role"], c["version_code"], c["path"], c["sha256"]))

    def test_manifest_follows_a_new_file(self):
        suite = self.cfg.suite_root
        self.put(suite, "com.ripostelabs.clock.apk", build_apk("com.ripostelabs.clock", 3))
        self.assertEqual(3, self.manifest()["apps"][0]["version_code"])
        blob = build_apk("com.ripostelabs.clock", 4, "1.4")
        self.put(suite, "com.ripostelabs.clock.apk", blob)
        os.utime(os.path.join(suite, "com.ripostelabs.clock.apk"), (1, 2))
        row = self.manifest()["apps"][0]
        self.assertEqual((4, digest(blob)), (row["version_code"], row["sha256"]))

    def test_broken_apk_is_left_out(self):
        self.put(self.cfg.suite_root, "com.ripostelabs.bad.apk", b"half a file")
        self.put(self.cfg.suite_root, "com.ripostelabs.clock.apk", build_apk("com.ripostelabs.clock", 3))
        self.assertEqual(["com.ripostelabs.clock"], [a["package"] for a in self.manifest()["apps"]])

    def test_files_served_with_range(self):
        blob = self.put(self.cfg.suite_root, "com.ripostelabs.clock.apk", build_apk("com.ripostelabs.clock", 3))
        status, _, body = self.call("GET", "/v1/releases/suite/com.ripostelabs.clock.apk")
        self.assertEqual((200, blob), (status, body))
        status, headers, body = self.call("GET", "/v1/releases/suite/com.ripostelabs.clock.apk",
                                          headers={"Range": "bytes=10-"})
        self.assertEqual((206, blob[10:]), (status, body))
        launcher = self.put(self.cfg.releases_root, "carlauncher-0.7-vc975.apk",
                            build_apk("com.ripostelabs.carlauncher", 975, "0.7", LAUNCHER_CERT))
        status, _, body = self.call("GET", "/v1/releases/carlauncher-0.7-vc975.apk")
        self.assertEqual((200, launcher), (status, body))

    def test_only_apks_are_served(self):
        self.put(self.cfg.releases_root, "index.html", b"<html>")
        status, _, _ = self.call("GET", "/v1/releases/index.html")
        self.assertEqual(404, status)
        status, _, _ = self.call("GET", "/v1/releases/suite/../carlauncher/x.apk")
        self.assertEqual(404, status)
        status, _, _ = self.call("GET", "/v1/releases/%2e%2e/models/x.apk")
        self.assertEqual(404, status)

    def test_untagged_peer_gets_no_manifest(self):
        self.tags = ["tag:laptop"]
        status, _, _ = self.call("GET", "/v1/releases/manifest.json")
        self.assertEqual(403, status)

    def test_no_release_roots_is_not_found(self):
        self.cfg.releases_root = None
        self.cfg.suite_root = None
        status, _, _ = self.call("GET", "/v1/releases/manifest.json")
        self.assertEqual(404, status)


if __name__ == "__main__":
    import unittest
    unittest.main()
