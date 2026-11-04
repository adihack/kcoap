"""Extract test vectors from RFC 3610, RFC 5869 and RFC 8613 text into Kotlin test sources.

Usage: python3 -I gen_vectors.py <dir-with-rfc-txt> <output-kotlin-dir>
"""
import re
import sys
from pathlib import Path

src, out = Path(sys.argv[1]), Path(sys.argv[2])
PKG = "com.example.kcoap.crypto.vectors"


def strip_pages(text: str) -> str:
    # Drop RFC page footers/headers and form feeds so multi-line values stay contiguous.
    lines = []
    for line in text.splitlines():
        if "\f" in line or re.match(r"^(RFC \d+ |\w.*\[Page \d+\]$)", line):
            continue
        lines.append(line)
    return "\n".join(lines)


def hexbytes(s: str) -> str:
    return re.sub(r"[^0-9a-fA-F]", "", s).lower()


# ---------------------------------------------------------------- RFC 3610
t = strip_pages((src / "rfc3610.txt").read_text())
ccm = []
for m in re.finditer(r"=+ Packet Vector #(\d+) =+\n(.*?)(?==+ Packet Vector #|\n\d+\.  |\Z)", t, re.S):
    n, body = int(m.group(1)), m.group(2)
    key = hexbytes(re.search(r"AES Key =(.*)", body).group(1))
    nonce = hexbytes(re.search(r"Nonce =(.*)", body).group(1))
    inp = re.search(r"Total packet length = (\d+)\. \[Input with (\d+) cleartext header octets\]\n((?:\s{10,}[0-9A-F ]+\n)+)", body)
    outp = re.search(r"Total packet length = (\d+)\. \[Authenticated and Encrypted Output\]\n((?:\s{10,}[0-9A-F ]+\n?)+)", body)
    in_len, hdr, in_hex = int(inp.group(1)), int(inp.group(2)), hexbytes(inp.group(3))
    out_len, out_hex = int(outp.group(1)), hexbytes(outp.group(2))
    assert len(in_hex) == 2 * in_len and len(out_hex) == 2 * out_len, n
    assert out_hex[: 2 * hdr] == in_hex[: 2 * hdr], n
    ccm.append(dict(n=n, key=key, nonce=nonce, aad=in_hex[: 2 * hdr], pt=in_hex[2 * hdr:],
                    ct=out_hex[2 * hdr:], tag=out_len - in_len))
assert len(ccm) == 24, len(ccm)

# ---------------------------------------------------------------- RFC 5869 (SHA-256 cases only)
t = strip_pages((src / "rfc5869.txt").read_text())
hkdf = []
for m in re.finditer(r"A\.(\d)\.  Test Case \d\n(.*?)(?=A\.\d\.  Test Case|\Z)", t, re.S):
    body = m.group(2)
    if "Hash = SHA-256" not in body:
        continue

    def field(name):
        f = re.search(name + r"\s+= (?:0x)?(.*?)\((\d+) octets\)", body, re.S)
        if f is None and name == "salt":
            return ""  # "salt = (0 octets)"
        v = hexbytes(f.group(1))
        assert len(v) == 2 * int(f.group(2)), (name, m.group(1))
        return v

    hkdf.append(dict(n=int(m.group(1)), ikm=field("IKM"), salt=field("salt"), info=field("info"),
                     length=int(re.search(r"L\s+= (\d+)", body).group(1)), prk=field("PRK"), okm=field("OKM")))
assert len(hkdf) == 3, len(hkdf)

# ---------------------------------------------------------------- RFC 8613 Appendix C
t = strip_pages((src / "rfc8613.txt").read_text())


def val(body, label):
    f = re.search(r"o\s+" + re.escape(label) + r":\s+0x([0-9a-f\s]*?)\s*\((\d+)\s+bytes?\)", body, re.S)
    v = hexbytes(f.group(1))
    assert len(v) == 2 * int(f.group(2)), label
    return v


kdf = []
for sec, side in [("C.1.1", "Client"), ("C.1.2", "Server"), ("C.2.1", "Client"), ("C.2.2", "Server"),
                  ("C.3.1", "Client"), ("C.3.2", "Server")]:
    body = re.search(re.escape(sec) + r"\.  " + side + r"\n(.*?)(?=\nC\.\d)", t, re.S).group(1)
    salt_m = re.search(r"Master Salt: 0x([0-9a-f]*)", body)
    kdf.append(dict(sec=sec, secret=val(body, "Master Secret"), salt=salt_m.group(1) if salt_m else "",
                    info_s=val(body, "info (for Sender Key)"), info_r=val(body, "info (for Recipient Key)"),
                    info_iv=val(body, "info (for Common IV)"), sender=val(body, "Sender Key"),
                    recipient=val(body, "Recipient Key"), iv=val(body, "Common IV")))

aead = []
for n in (4, 5, 6, 7):
    body = re.search(r"(?m)^C\.%d\.  Test Vector %d:.*?\n(.*?)(?=\nC\.\d+\.  Test Vector|\nAppendix D|\Z)" % (n, n), t, re.S).group(1)
    aead.append(dict(n=n, key=val(body, "encryption key"), nonce=val(body, "nonce"), aad=val(body, "AAD"),
                     pt=val(body, "plaintext"), ct=val(body, "ciphertext")))

# ---------------------------------------------------------------- emit Kotlin
out.mkdir(parents=True, exist_ok=True)
HDR = "// Generated from the RFC text by gen_vectors.py. Do not edit by hand.\npackage %s\n\n" % PKG

lines = [HDR, "internal data class CcmVector(val name: String, val key: String, val nonce: String, val aad: String,"
              " val plaintext: String, val ciphertext: String, val tagLength: Int)\n\n",
         "/** RFC 3610 §8 packet vectors 1-24 (ciphertext includes the tag). */\n",
         "internal val RFC3610_VECTORS: List<CcmVector> = listOf(\n"]
for v in ccm:
    lines.append('    CcmVector("RFC 3610 #%d", "%s", "%s", "%s", "%s", "%s", %d),\n'
                 % (v["n"], v["key"], v["nonce"], v["aad"], v["pt"], v["ct"], v["tag"]))
lines.append(")\n\n/** RFC 8613 Appendix C.4-C.7: AES-CCM-16-64-128 as used by OSCORE. */\n")
lines.append("internal val RFC8613_AEAD_VECTORS: List<CcmVector> = listOf(\n")
for v in aead:
    lines.append('    CcmVector("RFC 8613 C.%d", "%s", "%s", "%s", "%s", "%s", 8),\n'
                 % (v["n"], v["key"], v["nonce"], v["aad"], v["pt"], v["ct"]))
lines.append(")\n")
(out / "CcmVectors.kt").write_text("".join(lines))

lines = [HDR, "internal data class HkdfVector(val name: String, val ikm: String, val salt: String, val info: String,"
              " val length: Int, val prk: String?, val okm: String)\n\n",
         "/** RFC 5869 Appendix A.1-A.3 (SHA-256). */\n", "internal val RFC5869_VECTORS: List<HkdfVector> = listOf(\n"]
for v in hkdf:
    lines.append('    HkdfVector("RFC 5869 A.%d", "%s", "%s", "%s", %d, "%s", "%s"),\n'
                 % (v["n"], v["ikm"], v["salt"], v["info"], v["length"], v["prk"], v["okm"]))
lines.append(")\n\n/** RFC 8613 Appendix C.1-C.3: OSCORE key derivation with the given CBOR info structures. */\n")
lines.append("internal val RFC8613_KDF_VECTORS: List<HkdfVector> = listOf(\n")
for v in kdf:
    for label, info, okm, ln in [("Sender Key", v["info_s"], v["sender"], 16),
                                 ("Recipient Key", v["info_r"], v["recipient"], 16),
                                 ("Common IV", v["info_iv"], v["iv"], 13)]:
        lines.append('    HkdfVector("RFC 8613 %s %s", "%s", "%s", "%s", %d, null, "%s"),\n'
                     % (v["sec"], label, v["secret"], v["salt"], info, ln, okm))
lines.append(")\n")
(out / "HkdfVectors.kt").write_text("".join(lines))

print("ccm", len(ccm), "rfc8613-aead", len(aead), "hkdf", len(hkdf), "rfc8613-kdf", 3 * len(kdf))
print("tag lengths:", sorted({v["tag"] for v in ccm}))
