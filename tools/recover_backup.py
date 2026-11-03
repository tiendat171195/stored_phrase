#!/usr/bin/env python3
"""Decrypt a Stored Phrase backup file (.spbak) without the app.

Use it if the phone is lost and the app is gone:

    python3 -m pip install -r tools/requirements.txt
    python3 tools/recover_backup.py my-backup.spbak          # asks for the passphrase
    python3 tools/recover_backup.py my-backup.spbak --json   # machine-readable output

The format is specified in docs/BACKUP_FORMAT.md. Run this on an offline computer you trust:
the output contains your seed phrases in clear text.
"""
from __future__ import annotations

import argparse
import datetime
import getpass
import hashlib
import json
import struct
import sys
import unicodedata
from pathlib import Path

try:
    from argon2.low_level import Type, hash_secret_raw
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover
    sys.exit("Missing dependencies. Run: python3 -m pip install -r tools/requirements.txt")

MAGIC = b"SPBK"
LAYER2_MAGIC = b"SPL2"
HEADER_LENGTH = 45
WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
VALID_WORD_COUNTS = (12, 15, 18, 21, 24)
# Same limits as the app, so a hostile file cannot ask for absurd resources.
MEMORY_KIB_RANGE = (8 * 1024, 256 * 1024)
MAX_ITERATIONS = 16
MAX_PARALLELISM = 8
KIND_PLAIN = 1
KIND_LAYER2 = 2


class BackupError(Exception):
    pass


def load_wordlist(path: str | None = None) -> list[str]:
    file = Path(path) if path else Path(__file__).with_name("bip39-english.txt")
    raw = file.read_bytes()
    if hashlib.sha256(raw).hexdigest() != WORDLIST_SHA256:
        raise BackupError(f"{file} is not the official BIP39 English wordlist")
    return raw.decode("ascii").split("\n")[:-1]


def passphrase_bytes(passphrase: str) -> bytes:
    """NFKD-normalised UTF-8, exactly like the app (ASCII is used as is)."""
    if not passphrase.isascii():
        passphrase = unicodedata.normalize("NFKD", passphrase)
    return passphrase.encode("utf-8")


def argon2id(passphrase: str, salt: bytes, memory_kib: int, iterations: int, parallelism: int) -> bytes:
    return hash_secret_raw(
        passphrase_bytes(passphrase),
        salt,
        time_cost=iterations,
        memory_cost=memory_kib,
        parallelism=parallelism,
        hash_len=32,
        type=Type.ID,
    )


def check_kdf_params(memory_kib: int, iterations: int, parallelism: int) -> None:
    if not (MEMORY_KIB_RANGE[0] <= memory_kib <= MEMORY_KIB_RANGE[1]):
        raise BackupError("unsupported Argon2 memory size")
    if not (1 <= iterations <= MAX_ITERATIONS) or not (1 <= parallelism <= MAX_PARALLELISM):
        raise BackupError("unsupported Argon2 parameters")


def parse_header(data: bytes, magic: bytes = MAGIC) -> dict:
    """Header shared by backup files (SPBK) and layer-2 envelopes (SPL2)."""
    if len(data) < HEADER_LENGTH + 16:
        raise BackupError("data too short")
    if data[:4] != magic:
        raise BackupError(f"bad magic (expected {magic.decode()})")
    if data[4] != 1:
        raise BackupError(f"unsupported version {data[4]}")
    if data[5] != 1:
        raise BackupError(f"unsupported KDF {data[5]}")
    memory_kib, iterations = struct.unpack(">II", data[6:14])
    parallelism = data[14]
    check_kdf_params(memory_kib, iterations, parallelism)
    if data[15] != 16 or data[32] != 12:
        raise BackupError("bad salt or nonce length")
    return {
        "memory_kib": memory_kib,
        "iterations": iterations,
        "parallelism": parallelism,
        "salt": data[16:32],
        "nonce": data[33:45],
    }


def open_box(data: bytes, passphrase: str, magic: bytes) -> bytes:
    header = parse_header(data, magic)
    key = argon2id(passphrase, header["salt"], header["memory_kib"], header["iterations"], header["parallelism"])
    try:
        return AESGCM(key).decrypt(header["nonce"], data[HEADER_LENGTH:], data[:HEADER_LENGTH])
    except InvalidTag:
        raise BackupError("wrong passphrase, or the data was modified") from None


def decrypt_file(data: bytes, passphrase: str) -> bytes:
    return open_box(data, passphrase, MAGIC)


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.p = 0

    def take(self, n: int) -> bytes:
        if self.p + n > len(self.data):
            raise BackupError("truncated data")
        out = self.data[self.p:self.p + n]
        self.p += n
        return out

    def u8(self) -> int:
        return self.take(1)[0]

    def u16(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u32(self) -> int:
        return struct.unpack(">I", self.take(4))[0]

    def block(self) -> bytes:
        return self.take(self.u32())

    def at_end(self) -> bool:
        return self.p == len(self.data)


def bip39_checksum_ok(indices: list[int]) -> bool:
    bits = "".join(format(i, "011b") for i in indices)
    checksum_length = len(bits) // 33
    entropy_bits = bits[:-checksum_length]
    entropy = int(entropy_bits, 2).to_bytes(len(entropy_bits) // 8, "big")
    expected = format(hashlib.sha256(entropy).digest()[0], "08b")[:checksum_length]
    return bits[-checksum_length:] == expected


def decode_entry(content: bytes, words: list[str]) -> dict:
    """EntryCodec v1/v2 (see docs/ARCHITECTURE.md)."""
    r = Reader(content)
    version = r.u8()
    if version not in (1, 2):
        raise BackupError(f"unsupported entry version {version}")
    count = r.u8()
    if count not in VALID_WORD_COUNTS:
        raise BackupError(f"unsupported word count {count}")
    indices = [r.u16() for _ in range(count)]
    if any(i >= 2048 for i in indices):
        raise BackupError("word index out of range")
    bip39_passphrase = r.take(r.u16()).decode("utf-8")
    notes = r.take(r.u16()).decode("utf-8")
    birthday = None
    if version >= 2:
        flag = r.u8()
        if flag == 1:
            birthday = r.u32()
        elif flag != 0:
            raise BackupError("bad birthday flag")
    if not r.at_end():
        raise BackupError("trailing bytes in entry")
    return {
        "words": [words[i] for i in indices],
        "checksumValid": bip39_checksum_ok(indices),
        "bip39Passphrase": bip39_passphrase,
        "notes": notes,
        "birthdayHeight": birthday,
    }


def parse_payload(payload: bytes, words: list[str], layer2_passphrase=None) -> dict:
    """layer2_passphrase(meta) returns the entry's layer-2 passphrase, or None to skip it."""
    r = Reader(payload)
    if r.u8() != 1:
        raise BackupError("unsupported payload version")
    manifest = json.loads(r.block().decode("utf-8"))
    entries = []
    for _ in range(r.u16()):
        kind = r.u8()
        meta = json.loads(r.block().decode("utf-8"))
        content = r.block()
        if kind == KIND_PLAIN:
            entries.append({"kind": "plain", "meta": meta, **decode_entry(content, words)})
        elif kind == KIND_LAYER2:
            passphrase = layer2_passphrase(meta) if layer2_passphrase else None
            if passphrase:
                inner = open_box(content, passphrase, LAYER2_MAGIC)
                entries.append({"kind": "layer2", "meta": meta, **decode_entry(inner, words)})
            else:
                entries.append({"kind": "layer2", "meta": meta, "locked": True})
        else:
            raise BackupError(f"unknown entry kind {kind}")
    if not r.at_end():
        raise BackupError("trailing bytes in payload")
    return {"manifest": manifest, "entries": entries}


def recover(data: bytes, passphrase: str, words: list[str], layer2_passphrase=None) -> dict:
    return parse_payload(decrypt_file(data, passphrase), words, layer2_passphrase)


def format_time(ms: int | None) -> str:
    if not ms:
        return "-"
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def print_human(result: dict) -> None:
    manifest = result["manifest"]
    print(f"Backup created {format_time(manifest.get('createdAt'))} by {manifest.get('generator') or 'Stored Phrase'}")
    print(f"Entries: {len(result['entries'])}\n")
    for n, entry in enumerate(result["entries"], start=1):
        meta = entry["meta"]
        print(f"[{n}] {meta.get('label')} ({meta.get('wordCount')} words)")
        if entry.get("locked"):
            print("    Protected by a layer-2 passphrase; skipped.\n")
            continue
        status = "checksum OK" if entry["checksumValid"] else "CHECKSUM INVALID"
        print(f"    {status}")
        for i in range(0, len(entry["words"]), 4):
            row = entry["words"][i:i + 4]
            print("    " + "  ".join(f"{i + j + 1:>2}. {w:<9}" for j, w in enumerate(row)))
        if entry["bip39Passphrase"]:
            print(f"    BIP39 passphrase (25th word): {entry['bip39Passphrase']}")
        if entry["birthdayHeight"] is not None:
            print(f"    Birthday height: {entry['birthdayHeight']}")
        if entry["notes"]:
            print("    Notes: " + entry["notes"].replace("\n", "\n           "))
        print()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Decrypt a Stored Phrase backup (.spbak) without the app.")
    parser.add_argument("backup", help="path to the .spbak file")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    parser.add_argument("--wordlist", help="path to the BIP39 English wordlist (default: next to this script)")
    args = parser.parse_args(argv)
    try:
        words = load_wordlist(args.wordlist)
        data = Path(args.backup).read_bytes()
        parse_header(data)  # fail fast before asking for the passphrase
        passphrase = getpass.getpass("Backup passphrase: ")

        def ask_layer2(meta: dict) -> str | None:
            answer = getpass.getpass(f"Layer-2 passphrase for '{meta.get('label')}' (empty to skip): ")
            return answer or None

        result = recover(data, passphrase, words, ask_layer2)
    except BackupError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print_human(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
