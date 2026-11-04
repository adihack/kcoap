"""Extract the two EDHOC traces of RFC 9529 into a Kotlin test source.

Every value the RFC prints as a hex block under a header such as

    TH_2 (Raw Value) (32 bytes)
    c6 40 5c 15 ...

becomes a constant in `Rfc9529Trace1` (Section 2) or `Rfc9529Trace2` (Section 3). The byte count
in the header is checked against the hex that follows, so a parsing slip fails here instead of in a
test.

Naming: the header label is upper-cased (`TH_2`, `PRK_2E`, `INFO_FOR_MAC_2`). Raw values keep the
plain name; when the RFC prints the same value both raw and as a CBOR item, the CBOR form gets a
`_CBOR` suffix (`G_X_CBOR`), and the CBOR byte string form of context_x a `_BSTR` suffix. Values of
Section 3.1 (trace 2's rejected first message_1) are prefixed with `FIRST_`. The invalid messages of
Section 4 go to `Rfc9529InvalidTraces`, named after their subsection title.

Usage: python3 -I gen_edhoc_vectors.py <rfc9529.txt> <output.kt>
"""
import re
import sys
from pathlib import Path

source, output = Path(sys.argv[1]), Path(sys.argv[2])

# Headers without a name of their own take it from the line above; these give them readable names.
DESCRIPTIVE_NAMES = {
    "Initiator's ephemeral public key, one 'y'-coordinate": "G_X_Y_COORDINATE",
    "Responder's ephemeral public key, one 'y'-coordinate": "G_Y_Y_COORDINATE",
    "Responder's public authentication key, 'x'-coordinate": "PK_R_X",
    "Responder's public authentication key, 'y'-coordinate": "PK_R_Y",
    "Initiator's public authentication key, 'x'-coordinate": "PK_I_X",
    "Initiator's public authentication key, 'y'-coordinate": "PK_I_Y",
}

KIND_SUFFIX = {
    "raw value": "",
    "cbor sequence": "",
    "cbor data item": "_CBOR",
    "cbor byte string": "_BSTR",
}

HEADER = re.compile(r"^   (?P<label>.*?)\s*\((?P<length>\d+) bytes?\)$")
QUALIFIER = re.compile(r"\s*\(([^()]*)\)$")
QUALIFIERS = {"raw value", "cbor data item", "cbor sequence", "cbor byte string", "ecdh shared secret"}
HEX_LINE = re.compile(r"^   ([0-9a-fA-F]{2})( [0-9a-fA-F]{2})*$")
SECTION = re.compile(r"^(\d+(\.\d+)*)\.  ")


def strip_pages(text: str) -> list[str]:
    lines = []
    for line in text.splitlines():
        if "\f" in line or re.match(r"^RFC 9529 ", line) or re.search(r"\[Page \d+\]$", line):
            continue
        lines.append(line)
    return lines


def identifier(name: str) -> str:
    name = DESCRIPTIVE_NAMES.get(name, name)
    name = re.sub(r"[^0-9A-Za-z]+", "_", name).strip("_").upper()
    return name


def parse(lines: list[str]) -> dict[str, list[tuple[str, str, str]]]:
    """Returns {trace: [(section, name, kind, hex)]}."""
    traces: dict[str, list] = {"1": [], "2": [], "invalid": []}
    section = ""
    title = ""
    i = 0
    while i < len(lines):
        line = lines[i]
        heading = SECTION.match(line)
        if heading:
            section = heading.group(1)
            title = line[heading.end():].strip()
        trace = section.split(".")[0] if section else ""
        match = HEADER.match(line)
        if match and trace in ("2", "3", "4"):
            label = match.group("label").strip()
            qualifiers = []
            while (q := QUALIFIER.search(label)) and q.group(1).lower() in QUALIFIERS:
                qualifiers.insert(0, q.group(1).lower())
                label = label[: q.start()].strip()
            if not label:
                label = lines[i - 1].strip()
            kind = qualifiers[0] if qualifiers else "raw value"
            length = int(match.group("length"))
            hex_digits = ""
            j = i + 1
            while j < len(lines) and HEX_LINE.match(lines[j]):
                hex_digits += lines[j].replace(" ", "")
                j += 1
            assert len(hex_digits) == 2 * length, (section, label, length, len(hex_digits))
            if trace == "4":
                traces["invalid"].append((section, title, label, hex_digits.lower()))
            else:
                traces["1" if trace == "2" else "2"].append((section, label, kind, hex_digits.lower()))
            i = j
            continue
        i += 1
    return traces


def constants(entries) -> list[tuple[str, str, str]]:
    kinds_by_name: dict[str, set] = {}
    for section, label, kind, _ in entries:
        kinds_by_name.setdefault(identifier(label), set()).add(kind)
    result = []
    seen = set()
    for section, label, kind, value in entries:
        base = identifier(label)
        name = base
        if len(kinds_by_name[base]) > 1:
            name += KIND_SUFFIX.get(kind, "_" + identifier(kind))
        if section == "3.1":
            name = "FIRST_" + name
        assert name not in seen, (section, label, name)
        seen.add(name)
        result.append((name, f"RFC 9529 §{section}: {label} ({kind})", value))
    return result


def kotlin_object(name: str, title: str, entries) -> str:
    out = [f"/** {title} */", f"internal object {name} {{"]
    for constant, comment, value in constants(entries):
        out.append(f"    /** {comment} */")
        out.append(f'    const val {constant}: String = "{value}"')
    out.append("}")
    return "\n".join(out)


def invalid_object(entries) -> str:
    out = [
        "/** RFC 9529 §4: messages a compliant implementation must or may reject, named after their subsection. */",
        "internal object Rfc9529InvalidTraces {",
    ]
    for section, title, label, value in entries:
        out.append(f"    /** RFC 9529 §{section}: {label}. */")
        out.append(f'    const val {identifier(title)}: String = "{value}"')
    out.append("}")
    return "\n".join(out)


lines = strip_pages(source.read_text())
traces = parse(lines)
assert len(traces["1"]) > 80 and len(traces["2"]) > 80, {k: len(v) for k, v in traces.items()}
assert len(traces["invalid"]) == 15, len(traces["invalid"])

kotlin = "\n".join([
    "// Generated by tools/gen_edhoc_vectors.py from the text of RFC 9529. Do not edit.",
    "package dev.kcoap.edhoc.vectors",
    "",
    kotlin_object(
        "Rfc9529Trace1",
        "RFC 9529 §2: method 0, cipher suite 0, X.509 certificates identified by 'x5t'.",
        traces["1"],
    ),
    "",
    kotlin_object(
        "Rfc9529Trace2",
        "RFC 9529 §3: method 3, cipher suite 2 after negotiation, CCS identified by 'kid'.",
        traces["2"],
    ),
    "",
    invalid_object(traces["invalid"]),
    "",
])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(kotlin)
print(f"{output}: trace 1 {len(traces['1'])} values, trace 2 {len(traces['2'])} values, "
      f"{len(traces['invalid'])} invalid messages")
