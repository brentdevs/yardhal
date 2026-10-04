#!/usr/bin/env python3
import base64
import hashlib
import struct
import stringprep
import sys
import unicodedata
import zlib

ucd = unicodedata.ucd_3_2_0


def ranges(predicate):
    result = []
    start = None
    for code_point in range(0x110000):
        if predicate(chr(code_point)):
            if start is None:
                start = code_point
        elif start is not None:
            result.extend((start, code_point - 1))
            start = None
    if start is not None:
        result.extend((start, 0x10FFFF))
    return result


def prohibited(character):
    return any(predicate(character) for predicate in (
        stringprep.in_table_c12,
        stringprep.in_table_c21,
        stringprep.in_table_c22,
        stringprep.in_table_c3,
        stringprep.in_table_c4,
        stringprep.in_table_c5,
        stringprep.in_table_c6,
        stringprep.in_table_c7,
        stringprep.in_table_c8,
        stringprep.in_table_c9,
    ))


tables = [ranges(predicate) for predicate in (
    stringprep.in_table_b1,
    stringprep.in_table_c12,
    prohibited,
    stringprep.in_table_a1,
    stringprep.in_table_d1,
    stringprep.in_table_d2,
)]
combining_classes = []
decompositions = []
decomposition_values = []
compositions = []
for code_point in range(0x110000):
    character = chr(code_point)
    combining_class = ucd.combining(character)
    if combining_class:
        combining_classes.extend((code_point, combining_class))
    if 0xAC00 <= code_point <= 0xD7A3:
        continue
    expanded = ucd.normalize("NFKD", character)
    if expanded != character:
        decompositions.extend((code_point, len(decomposition_values), len(expanded)))
        decomposition_values.extend(map(ord, expanded))
    decomposition = ucd.decomposition(character)
    if decomposition and not decomposition.startswith("<"):
        pair = [int(part, 16) for part in decomposition.split()]
        if len(pair) == 2 and ucd.normalize("NFC", "".join(map(chr, pair))) == character:
            compositions.append((*pair, code_point))
compositions.sort()
tables.extend((
    combining_classes,
    decompositions,
    decomposition_values,
    [value for triple in compositions for value in triple],
))
payload = b"".join(
    struct.pack(">I", len(table)) + struct.pack(">" + "I" * len(table), *table)
    for table in tables
)
digest = hashlib.sha256(payload).hexdigest()
encoded = base64.b64encode(zlib.compress(payload, level=9)).decode("ascii")

chunks = [encoded[index:index + 116] for index in range(0, len(encoded), 116)]
print("    private const val ENCODED =")
for index, chunk in enumerate(chunks):
    suffix = " +" if index + 1 < len(chunks) else ""
    print(f'        "{chunk}"{suffix}')
print(f"Unicode {ucd.unidata_version}; uncompressed table SHA256: {digest}", file=sys.stderr)
