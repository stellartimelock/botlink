"""Read package name, versionCode, and signing cert SHA-256 from an APK.

Used by `--auto` so the PC can pick send-apk vs replace-apk from the
phone's installed cert + versionCode. Stdlib + cryptography only.
"""

from __future__ import annotations

import hashlib
import struct
import zipfile
from pathlib import Path
from typing import Any

_EOCD_MAGIC = b"PK\x05\x06"
_SIG_MAGIC = b"APK Sig Block 42"
_V2_ID = 0x7109871A
_V3_ID = 0xF05368C0
_RES_XML = 0x0003
_RES_STRING_POOL = 0x0001
_RES_XML_START_ELEMENT = 0x0102
_RES_XML_RESOURCE_MAP = 0x0180
_TYPE_STRING = 3
_TYPE_INT_DEC = 16
_TYPE_INT_HEX = 17
_ATTR_VERSION_CODE = 0x0101021B


class ApkMetaError(RuntimeError):
    pass


def normalize_cert(sha256: str) -> str:
    return sha256.lower().replace(":", "").replace(" ", "").strip()


def read_apk_meta(path: str | Path) -> dict[str, Any]:
    file_path = Path(path)
    if not file_path.is_file():
        raise ApkMetaError(f"APK not found: {file_path}")
    data = file_path.read_bytes()
    package, version_code = _manifest_fields(data)
    cert = _signer_sha256(data)
    return {
        "package": package,
        "versionCode": version_code,
        "certSha256": cert,
        "path": str(file_path),
        "name": file_path.name,
        "size": file_path.stat().st_size,
    }


def _signer_sha256(data: bytes) -> str:
    cert = _v3_or_v2_cert(data)
    if cert is None:
        cert = _v1_cert(data)
    if cert is None:
        raise ApkMetaError("No signing certificate in APK.")
    return hashlib.sha256(cert).hexdigest()


def _v3_or_v2_cert(data: bytes) -> bytes | None:
    block = _signing_block(data)
    if block is None:
        return None
    pairs = _id_values(block)
    for key in (_V3_ID, _V2_ID):
        blob = pairs.get(key)
        if not blob:
            continue
        cert = _first_signer_cert(blob)
        if cert:
            return cert
    return None


def _signing_block(data: bytes) -> bytes | None:
    eocd = data.rfind(_EOCD_MAGIC)
    if eocd < 0 or eocd + 22 > len(data):
        return None
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset < 32 or cd_offset > eocd:
        return None
    footer = cd_offset - 24
    if data[cd_offset - 16 : cd_offset] != _SIG_MAGIC:
        return None
    size = struct.unpack_from("<Q", data, footer)[0]
    # size is the block length excluding the leading uint64. Block starts size+8 before CD.
    start = cd_offset - size - 8
    if start < 0:
        return None
    header_size = struct.unpack_from("<Q", data, start)[0]
    if header_size != size:
        return None
    return data[start + 8 : footer]


def _id_values(block: bytes) -> dict[int, bytes]:
    out: dict[int, bytes] = {}
    pos = 0
    while pos + 12 <= len(block):
        length = struct.unpack_from("<Q", block, pos)[0]
        ident = struct.unpack_from("<I", block, pos + 8)[0]
        value_len = length - 4
        start = pos + 12
        end = start + value_len
        if value_len < 0 or end > len(block):
            break
        out[ident] = block[start:end]
        pos = end
    return out


def _first_signer_cert(blob: bytes) -> bytes | None:
    signers, _ = _u32_pref(blob, 0)
    if signers is None:
        return None
    signer, _ = _u32_pref(signers, 0)
    if signer is None:
        return None
    signed, _ = _u32_pref(signer, 0)
    if signed is None:
        return None
    # signed data: digests, certificates, additional attributes
    _digests, pos = _u32_pref(signed, 0)
    certs, _ = _u32_pref(signed, pos)
    if certs is None:
        return None
    cert, _ = _u32_pref(certs, 0)
    return cert


def _u32_pref(buf: bytes, offset: int) -> tuple[bytes | None, int]:
    if offset + 4 > len(buf):
        return None, offset
    length = struct.unpack_from("<I", buf, offset)[0]
    start = offset + 4
    end = start + length
    if end > len(buf) or length < 0:
        return None, offset
    return buf[start:end], end


def _v1_cert(data: bytes) -> bytes | None:
    from io import BytesIO

    try:
        zf = zipfile.ZipFile(BytesIO(data))
    except zipfile.BadZipFile:
        return None
    names = [n for n in zf.namelist() if n.upper().startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))]
    if not names:
        return None
    raw = zf.read(names[0])
    try:
        from cryptography.hazmat.primitives.serialization import Encoding
        from cryptography.hazmat.primitives.serialization.pkcs7 import load_der_pkcs7_certificates

        certs = load_der_pkcs7_certificates(raw)
        if not certs:
            return None
        return certs[0].public_bytes(Encoding.DER)
    except Exception:
        return None


def _manifest_fields(data: bytes) -> tuple[str, int]:
    from io import BytesIO

    try:
        zf = zipfile.ZipFile(BytesIO(data))
        xml = zf.read("AndroidManifest.xml")
    except (zipfile.BadZipFile, KeyError) as exc:
        raise ApkMetaError("APK has no AndroidManifest.xml") from exc
    return _parse_manifest(xml)


def _parse_manifest(xml: bytes) -> tuple[str, int]:
    if len(xml) < 8:
        raise ApkMetaError("AndroidManifest.xml is truncated.")
    typ, header_size, file_size = struct.unpack_from("<HHI", xml, 0)
    if typ != _RES_XML:
        raise ApkMetaError("AndroidManifest.xml is not binary XML.")
    pos = header_size
    strings: list[str] = []
    res_ids: list[int] = []
    package = ""
    version_code = 0
    while pos + 8 <= min(file_size, len(xml)):
        chunk_type, chunk_header, chunk_size = struct.unpack_from("<HHI", xml, pos)
        if chunk_size < 8 or pos + chunk_size > len(xml):
            break
        if chunk_type == _RES_STRING_POOL:
            strings = _string_pool(xml, pos)
        elif chunk_type == _RES_XML_RESOURCE_MAP:
            res_ids = _resource_ids(xml, pos, chunk_size)
        elif chunk_type == _RES_XML_START_ELEMENT:
            pkg, ver = _start_element(xml, pos, strings, res_ids)
            if pkg:
                package = pkg
            if ver is not None:
                version_code = ver
        pos += chunk_size
    if not package:
        raise ApkMetaError("Could not read package name from the APK.")
    return package, version_code


def _string_pool(buf: bytes, offset: int) -> list[str]:
    _typ, header_size, _size, string_count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<HHIIIIII",
        buf,
        offset,
    )
    utf8 = bool(flags & (1 << 8))
    offsets_at = offset + header_size
    data_at = offset + strings_start
    out: list[str] = []
    for i in range(string_count):
        off = struct.unpack_from("<I", buf, offsets_at + i * 4)[0]
        out.append(_decode_string(buf, data_at + off, utf8))
    return out


def _decode_string(buf: bytes, pos: int, utf8: bool) -> str:
    if pos >= len(buf):
        return ""
    if utf8:
        char_len, pos = _utf8_len(buf, pos)
        byte_len, pos = _utf8_len(buf, pos)
        end = min(pos + byte_len, len(buf))
        return buf[pos:end].decode("utf-8", errors="replace")
    if pos + 2 > len(buf):
        return ""
    char_len = struct.unpack_from("<H", buf, pos)[0]
    pos += 2
    if char_len & 0x8000:
        if pos + 2 > len(buf):
            return ""
        char_len = ((char_len & 0x7FFF) << 16) | struct.unpack_from("<H", buf, pos)[0]
        pos += 2
    end = min(pos + char_len * 2, len(buf))
    return buf[pos:end].decode("utf-16-le", errors="replace")


def _utf8_len(buf: bytes, pos: int) -> tuple[int, int]:
    if pos >= len(buf):
        return 0, pos
    first = buf[pos]
    pos += 1
    if first & 0x80:
        if pos >= len(buf):
            return first & 0x7F, pos
        return ((first & 0x7F) << 8) | buf[pos], pos + 1
    return first, pos


def _resource_ids(buf: bytes, offset: int, chunk_size: int) -> list[int]:
    out: list[int] = []
    pos = offset + 8
    end = offset + chunk_size
    while pos + 4 <= end:
        out.append(struct.unpack_from("<I", buf, pos)[0])
        pos += 4
    return out


def _start_element(
    buf: bytes,
    offset: int,
    strings: list[str],
    res_ids: list[int],
) -> tuple[str, int | None]:
    # after chunk header: lineNumber, comment, ns, name, attrStart, attrSize, attrCount, idIndex, classIndex, styleIndex
    if offset + 36 > len(buf):
        return "", None
    name_idx = struct.unpack_from("<I", buf, offset + 20)[0]
    name = _s(strings, name_idx)
    attr_start, attr_size, attr_count = struct.unpack_from("<HHH", buf, offset + 24)
    if attr_size < 20:
        attr_size = 20
    # attributeStart is from ResXMLTree_attrExt (chunk + 16). Typical value 20 → first attr at +36.
    attrs_at = offset + 16 + attr_start
    package = ""
    version: int | None = None
    if name != "manifest":
        return "", None
    for i in range(attr_count):
        a = attrs_at + i * attr_size
        if a + 20 > len(buf):
            break
        _ns, aname, raw, size_type, data = struct.unpack_from("<IIIII", buf, a)
        data_type = (size_type >> 24) & 0xFF
        attr_name = _s(strings, aname)
        if attr_name == "package":
            if data_type == _TYPE_STRING:
                package = _s(strings, data) or _s(strings, raw)
            elif raw != 0xFFFFFFFF:
                package = _s(strings, raw)
        if _is_version_code(attr_name, aname, res_ids):
            parsed = _int_attr(data_type, data)
            if parsed is not None:
                version = parsed
    return package, version


def _is_version_code(attr_name: str, aname: int, res_ids: list[int]) -> bool:
    if attr_name in {"versionCode", "version_code"}:
        return True
    if aname == _ATTR_VERSION_CODE:
        return True
    if 0 <= aname < len(res_ids) and res_ids[aname] == _ATTR_VERSION_CODE:
        return True
    return False


def _int_attr(data_type: int, data: int) -> int | None:
    if data_type in {_TYPE_INT_DEC, _TYPE_INT_HEX}:
        return data
    return None


def _s(strings: list[str], idx: int) -> str:
    if 0 <= idx < len(strings):
        return strings[idx]
    return ""
