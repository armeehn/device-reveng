"""apkinfo: what an APK is and who signed it, read with the standard library only.

The release manifest (ingest.py, GET /v1/releases/manifest.json) lists every APK the car may
install. Each row needs the package, its versionCode and the signing certificate, and the
estate host that serves it has no Android SDK. So this reads them straight from the file:

    APK = zip
      ├─ AndroidManifest.xml      binary XML (AXML): <manifest package= versionCode= versionName=>
      └─ APK Signing Block        between the last entry and the central directory
           ├─ v3 (0xf05368c0)     signer -> signed data -> certificates[0]   (preferred)
           └─ v2 (0x7109871a)     same layout

The certificate digest here is informational. The car pins its own copy and checks the file it
downloaded, so a wrong value in the manifest can only make an update fail, never succeed.
"""

import hashlib
import struct
import zipfile

EOCD_MAGIC = b"PK\x05\x06"
EOCD_MIN = 22
EOCD_MAX_COMMENT = 0xFFFF
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIG_V2 = 0x7109871A
SIG_V3 = 0xF05368C0

AXML_STRING_POOL = 0x0001
AXML_START_ELEMENT = 0x0102
AXML_UTF8 = 0x100
AXML_ATTR_BYTES = 20
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
NO_INDEX = 0xFFFFFFFF


class ApkError(ValueError):
    pass


def _u32(b, at):
    return struct.unpack_from("<I", b, at)[0]


def _u16(b, at):
    return struct.unpack_from("<H", b, at)[0]


# --- signing block --------------------------------------------------------------------------

def _central_dir_offset(data):
    """Offset of the zip central directory, from the end-of-central-directory record."""
    lo = max(0, len(data) - EOCD_MIN - EOCD_MAX_COMMENT)
    at = data.rfind(EOCD_MAGIC, lo)
    if at < 0:
        raise ApkError("not a zip: no end of central directory")
    return _u32(data, at + 16)


def _sig_pairs(data):
    """The id -> value pairs of the APK Signing Block. Empty if the APK has none (v1 only)."""
    cd = _central_dir_offset(data)
    if cd < 24 or data[cd - 16:cd] != SIG_BLOCK_MAGIC:
        return {}
    size = struct.unpack_from("<Q", data, cd - 24)[0]
    start = cd - size - 8
    if start < 0 or struct.unpack_from("<Q", data, start)[0] != size:
        raise ApkError("signing block sizes disagree")

    pairs, at, end = {}, start + 8, cd - 24
    while at + 12 <= end:
        n = struct.unpack_from("<Q", data, at)[0]
        pid = _u32(data, at + 8)
        pairs[pid] = data[at + 12:at + 8 + n]
        at += 8 + n
    return pairs


def _prefixed(b, at):
    """A uint32 length-prefixed slice at [at]: (slice, offset after it)."""
    n = _u32(b, at)
    if at + 4 + n > len(b):
        raise ApkError("length prefix runs past its block")
    return b[at + 4:at + 4 + n], at + 4 + n


def _first_cert(scheme_value):
    """certificates[0] of signers[0]: signers -> signer -> signed data -> digests, certificates."""
    signers, _ = _prefixed(scheme_value, 0)
    signer, _ = _prefixed(signers, 0)
    signed, _ = _prefixed(signer, 0)
    _, at = _prefixed(signed, 0)              # digests
    certs, _ = _prefixed(signed, at)
    cert, _ = _prefixed(certs, 0)
    return cert


def cert_sha256(data):
    """SHA-256 of the first signer's certificate (DER), from v3 or else v2. None if unsigned."""
    pairs = _sig_pairs(data)
    for scheme in (SIG_V3, SIG_V2):
        if scheme in pairs:
            return hashlib.sha256(_first_cert(pairs[scheme])).hexdigest()
    return None


# --- binary manifest ------------------------------------------------------------------------

def _strings(b, at):
    count = _u32(b, at + 8)
    flags = _u32(b, at + 16)
    strings_start = at + _u32(b, at + 20)
    utf8 = bool(flags & AXML_UTF8)
    out = []
    for i in range(count):
        p = strings_start + _u32(b, at + 28 + 4 * i)
        if utf8:
            p += 2 if b[p] & 0x80 else 1           # UTF-16 length, skipped
            n = b[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | b[p + 1]
                p += 1
            out.append(b[p + 1:p + 1 + n].decode("utf-8", "replace"))
        else:
            n = _u16(b, p)
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | _u16(b, p + 2)
                p += 2
            out.append(b[p + 2:p + 2 + 2 * n].decode("utf-16-le", "replace"))
    return out


def manifest_attrs(axml):
    """package, versionCode and versionName from the <manifest> element of a binary manifest."""
    at, strings = 8, []
    while at + 8 <= len(axml):
        kind, header, size = _u16(axml, at), _u16(axml, at + 2), _u32(axml, at + 4)
        if size < 8:
            raise ApkError("bad AXML chunk")
        if kind == AXML_STRING_POOL:
            strings = _strings(axml, at)
        elif kind == AXML_START_ELEMENT:
            body = at + header
            if strings[_u32(axml, body + 4)] == "manifest":
                return _read_attrs(axml, body, strings)
        at += size
    raise ApkError("no <manifest> element")


def _read_attrs(b, body, strings):
    first, width, count = _u16(b, body + 8), _u16(b, body + 10), _u16(b, body + 12)
    width = width or AXML_ATTR_BYTES
    found = {}
    for i in range(count):
        a = body + first + i * width
        name = strings[_u32(b, a + 4)]
        raw = _u32(b, a + 8)
        dtype, value = b[a + 15], _u32(b, a + 16)
        if dtype == TYPE_STRING or raw != NO_INDEX:
            found[name] = strings[raw if raw != NO_INDEX else value]
        elif dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
            found[name] = value
    return {
        "package": found.get("package"),
        "version_code": int(found["versionCode"]) if "versionCode" in found else None,
        "version_name": found.get("versionName"),
    }


def read(path):
    """Everything the release manifest needs about one APK file."""
    with open(path, "rb") as f:
        data = f.read()
    try:
        with zipfile.ZipFile(path) as z:
            attrs = manifest_attrs(z.read("AndroidManifest.xml"))
    except (zipfile.BadZipFile, KeyError) as e:
        raise ApkError(f"not an APK: {e}")
    return dict(attrs, sha256=hashlib.sha256(data).hexdigest(), size=len(data),
                cert_sha256=cert_sha256(data))
