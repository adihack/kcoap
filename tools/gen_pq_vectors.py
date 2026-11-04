"""Extract a few NIST ACVP ML-KEM (FIPS 203) and ML-DSA (FIPS 204) test cases into Kotlin test sources.

Usage: python3 -I gen_pq_vectors.py <dir-with-acvp-json> <output-kotlin-dir> <acvp-server-commit>

The input directory holds the internalProjection.json of each ACVP-Server gen-val/json-files directory, saved as
<directory name>.json (ML-KEM-keyGen-FIPS203.json, ...). Only the listed tcIds are kept: one case per parameter set
and function, plus the negative cases. ML-DSA cases all use the external interface, pure ML-DSA and an empty
context, the only variant COSE uses.
"""
import json
import sys
from pathlib import Path

src, out, commit = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
PKG = "dev.kcoap.crypto.bc.vectors"
WIDTH = 96  # hex digits per line inside multi-line strings

# FIPS 203 Table 3 (ek, dk, c) and FIPS 204 Table 2 (pk, sk, signature), to catch a wrong pick early.
KEM_SIZES = {"ML-KEM-512": (800, 1632, 768), "ML-KEM-768": (1184, 2400, 1088), "ML-KEM-1024": (1568, 3168, 1568)}
DSA_SIZES = {"ML-DSA-44": (1312, 2560, 2420), "ML-DSA-65": (1952, 4032, 3309), "ML-DSA-87": (2592, 4896, 4627)}

KEM_KEYGEN = "ML-KEM-keyGen-FIPS203"
KEM_ENCAP_DECAP = "ML-KEM-encapDecap-FIPS203"
DSA_KEYGEN = "ML-DSA-keyGen-FIPS204"
DSA_SIGGEN = "ML-DSA-sigGen-FIPS204"
DSA_SIGVER = "ML-DSA-sigVer-FIPS204"


def cases(file, tc_ids):
    doc = json.loads((src / f"{file}.json").read_text())
    found = {}
    for group in doc["testGroups"]:
        for test in group["tests"]:
            if test["tcId"] in tc_ids:
                found[test["tcId"]] = (group, test)
    assert sorted(found) == sorted(tc_ids), (file, sorted(found))
    return [found[i] for i in tc_ids]


def size(hex_value):
    return len(hex_value) // 2


def code(expression):
    """A Kotlin expression, as opposed to a hex value."""
    return (expression,)


def enum(prefix, group):
    return code(f"{prefix}.{group['parameterSet'].replace('-', '_')}")


def kotlin_hex(value, indent):
    value = value.lower()
    if len(value) <= WIDTH:
        return f'"{value}"'
    pad = " " * (indent + 4)
    lines = [pad + value[i:i + WIDTH] for i in range(0, len(value), WIDTH)]
    return '"""\n' + "\n".join(lines) + "\n" + " " * indent + '"""'


def vector(cls, file, group, t, fields, note=None):
    origin = f"{file} tgId {group['tgId']} tcId {t['tcId']}"
    body = [f"    // {origin}" + (f": {note}" if note else ""), f"    {cls}(",
            f'        name = "{file} tcId {t["tcId"]}",']
    for key, value in fields:
        rendered = value[0] if isinstance(value, tuple) else kotlin_hex(value, 8)
        body.append(f"        {key} = {rendered},")
    body.append("    ),")
    return "\n".join(body)


def declaration(header, items):
    return header + "\n" + "\n".join(items) + "\n)"


def write(file_name, imports, declarations):
    header = [
        f"// Generated from NIST ACVP-Server (github.com/usnistgov/ACVP-Server, commit {commit[:12]},",
        "// gen-val/json-files/*/internalProjection.json) by tools/gen_pq_vectors.py. Do not edit by hand.",
        f"package {PKG}",
        "",
    ]
    header += [f"import {i}" for i in imports] + ["", ""]
    (out / file_name).write_text("\n".join(header) + "\n\n".join(declarations) + "\n")


# ---------------------------------------------------------------- ML-KEM
kem = []

items = []
for group, t in cases(KEM_KEYGEN, [1, 26, 51]):
    assert (size(t["ek"]), size(t["dk"])) == KEM_SIZES[group["parameterSet"]][:2]
    items.append(vector("MlKemKeyGenVector", KEM_KEYGEN, group, t, [
        ("type", enum("KeyType", group)), ("d", t["d"]), ("z", t["z"]), ("ek", t["ek"]), ("dk", t["dk"]),
    ]))
kem.append(declaration("""/** ML-KEM.KeyGen_internal(d, z) = (ek, dk), FIPS 203 Algorithm 16. */
internal class MlKemKeyGenVector(
    val name: String,
    val type: KeyType,
    val d: String,
    val z: String,
    val ek: String,
    val dk: String,
)

/** ML-KEM-keyGen-FIPS203: the first case of each parameter set. */
internal val ML_KEM_KEYGEN_VECTORS: List<MlKemKeyGenVector> = listOf(""", items))

items = []
for group, t in cases(KEM_ENCAP_DECAP, [1, 26, 51]):
    assert group["function"] == "encapsulation"
    assert (size(t["ek"]), size(t["dk"]), size(t["c"])) == KEM_SIZES[group["parameterSet"]]
    items.append(vector("MlKemEncapsulationVector", KEM_ENCAP_DECAP, group, t, [
        ("algorithm", enum("KemAlgorithm", group)),
        ("ek", t["ek"]), ("dk", t["dk"]), ("m", t["m"]), ("c", t["c"]), ("k", t["k"]),
    ]))
kem.append(declaration("""/** ML-KEM.Encaps_internal(ek, m) = (k, c), FIPS 203 Algorithm 17; [dk] decapsulates [c] back to [k]. */
internal class MlKemEncapsulationVector(
    val name: String,
    val algorithm: KemAlgorithm,
    val ek: String,
    val dk: String,
    val m: String,
    val c: String,
    val k: String,
)

/** ML-KEM-encapDecap-FIPS203, encapsulation groups: the first case of each parameter set. */
internal val ML_KEM_ENCAPSULATION_VECTORS: List<MlKemEncapsulationVector> = listOf(""", items))

items = []
for group, t in cases(KEM_ENCAP_DECAP, [76]):
    assert group["function"] == "decapsulation" and t["reason"] == "modified ciphertext"
    items.append(vector("MlKemDecapsulationVector", KEM_ENCAP_DECAP, group, t, [
        ("algorithm", enum("KemAlgorithm", group)), ("ek", t["ek"]), ("dk", t["dk"]), ("c", t["c"]), ("k", t["k"]),
    ], note=t["reason"]))
kem.append(declaration("""/** ML-KEM.Decaps_internal(dk, c) = k for a modified [c]: [k] is the implicit-rejection value J(z || c). */
internal class MlKemDecapsulationVector(
    val name: String,
    val algorithm: KemAlgorithm,
    val ek: String,
    val dk: String,
    val c: String,
    val k: String,
)

/** ML-KEM-encapDecap-FIPS203, decapsulation group of ML-KEM-512: a ciphertext NIST modified. */
internal val ML_KEM_IMPLICIT_REJECTION_VECTORS: List<MlKemDecapsulationVector> = listOf(""", items))

(group, t), = cases(KEM_ENCAP_DECAP, [117])
assert group["function"] == "encapsulationKeyCheck" and t["testPassed"] is False
kem.append(f"""/**
 * {KEM_ENCAP_DECAP} tgId {group['tgId']} tcId {t['tcId']} ({group['parameterSet']}, encapsulationKeyCheck): an
 * encapsulation key that fails the modulus check of FIPS 203 §7.2 ("{t['reason']}").
 */
internal const val ML_KEM_512_INVALID_EK: String = {kotlin_hex(t["ek"], 0)}""")

(group, t), = cases(KEM_ENCAP_DECAP, [106])
assert group["function"] == "decapsulationKeyCheck" and t["testPassed"] is False
kem.append(f"""/**
 * {KEM_ENCAP_DECAP} tgId {group['tgId']} tcId {t['tcId']} ({group['parameterSet']}, decapsulationKeyCheck): a
 * decapsulation key that fails the hash check of FIPS 203 §7.3 ("{t['reason']}").
 */
internal const val ML_KEM_512_INVALID_DK: String = {kotlin_hex(t["dk"], 0)}

/** The encapsulation key of the same test case. */
internal const val ML_KEM_512_INVALID_DK_EK: String = {kotlin_hex(t["ek"], 0)}""")

write("MlKemVectors.kt", ["dev.kcoap.crypto.KemAlgorithm", "dev.kcoap.crypto.KeyType"], kem)

# ---------------------------------------------------------------- ML-DSA
dsa = []


def check_pure_empty_context(group, t):
    assert group["signatureInterface"] == "external" and group["preHash"] == "pure", t["tcId"]
    assert t["context"] == "", t["tcId"]
    pk, sk, signature = DSA_SIZES[group["parameterSet"]]
    assert (size(t["pk"]), size(t["sk"]), size(t["signature"])) == (pk, sk, signature), t["tcId"]


items = []
for group, t in cases(DSA_KEYGEN, [1, 26, 51]):
    assert (size(t["pk"]), size(t["sk"])) == DSA_SIZES[group["parameterSet"]][:2]
    items.append(vector("MlDsaKeyGenVector", DSA_KEYGEN, group, t, [
        ("algorithm", enum("SignatureAlgorithm", group)), ("seed", t["seed"]), ("pk", t["pk"]), ("sk", t["sk"]),
    ]))
dsa.append(declaration("""/** ML-DSA.KeyGen_internal(seed) = (pk, sk), FIPS 204 Algorithm 6. */
internal class MlDsaKeyGenVector(
    val name: String,
    val algorithm: SignatureAlgorithm,
    val seed: String,
    val pk: String,
    val sk: String,
)

/** ML-DSA-keyGen-FIPS204: the first case of each parameter set. */
internal val ML_DSA_KEYGEN_VECTORS: List<MlDsaKeyGenVector> = listOf(""", items))

items = []
for group, t in cases(DSA_SIGGEN, [5, 44, 70, 189]):
    check_pure_empty_context(group, t)
    rnd = t.get("rnd")
    assert (rnd is None) == group["deterministic"], t["tcId"]
    items.append(vector("MlDsaSigGenVector", DSA_SIGGEN, group, t, [
        ("algorithm", enum("SignatureAlgorithm", group)), ("rnd", code("null") if rnd is None else rnd),
        ("pk", t["pk"]), ("sk", t["sk"]), ("message", t["message"]), ("signature", t["signature"]),
    ], note="deterministic" if rnd is None else "hedged"))
dsa.append(declaration("""/**
 * ML-DSA.Sign(sk, message, ctx = "") = signature, FIPS 204 Algorithm 2: deterministic when [rnd] is null, hedged
 * with the given 32-byte [rnd] otherwise.
 */
internal class MlDsaSigGenVector(
    val name: String,
    val algorithm: SignatureAlgorithm,
    val rnd: String?,
    val pk: String,
    val sk: String,
    val message: String,
    val signature: String,
)

/**
 * ML-DSA-sigGen-FIPS204, external interface, pure, empty context: one deterministic case per parameter set and
 * one hedged ML-DSA-44 case.
 */
internal val ML_DSA_SIGGEN_VECTORS: List<MlDsaSigGenVector> = listOf(""", items))

items = []
for group, t in cases(DSA_SIGVER, [10]):
    check_pure_empty_context(group, t)
    items.append(vector("MlDsaSigVerVector", DSA_SIGVER, group, t, [
        ("algorithm", enum("SignatureAlgorithm", group)),
        ("pk", t["pk"]), ("message", t["message"]), ("signature", t["signature"]),
        ("isValid", code("true" if t["testPassed"] else "false")),
    ], note=t["reason"]))
dsa.append(declaration("""/** ML-DSA.Verify(pk, message, signature, ctx = "") = [isValid], FIPS 204 Algorithm 3. */
internal class MlDsaSigVerVector(
    val name: String,
    val algorithm: SignatureAlgorithm,
    val pk: String,
    val message: String,
    val signature: String,
    val isValid: Boolean,
)

/** ML-DSA-sigVer-FIPS204, external interface, pure, empty context: an ML-DSA-44 signature over a modified message. */
internal val ML_DSA_SIGVER_VECTORS: List<MlDsaSigVerVector> = listOf(""", items))

write("MlDsaVectors.kt", ["dev.kcoap.crypto.SignatureAlgorithm"], dsa)
