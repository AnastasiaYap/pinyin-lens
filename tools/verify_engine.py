#!/usr/bin/env python3
"""Mirror of PinyinEngine's segmentation, run against the real assets.

Keeps the Kotlin honest: if a change to build_dict.py breaks alignment or
regresses a polyphone, this catches it without an emulator in the loop.
"""

import sys
import unicodedata
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"
MAX_WORD_LEN = 6


def load(name):
    """Returns {key: value}, where value is "" for a key-only line."""
    table = {}
    for line in (ASSETS / name).read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("\t")
        if key:
            table[key] = value
    return table


def check_sort_order(name):
    """The app binary-searches these files, so their order is load-bearing.

    Kotlin's String.compareTo orders by UTF-16 code unit, which differs from
    Python's code-point order for supplementary characters. If build_dict.py
    ever loses its utf16_key sort, lookups would fail for a subset of entries
    and nothing else would notice.
    """
    keys = [
        line.partition("\t")[0]
        for line in (ASSETS / name).read_text(encoding="utf-8").splitlines()
        if line
    ]
    encoded = [k.encode("utf-16-be") for k in keys]
    for i in range(1, len(encoded)):
        if encoded[i - 1] >= encoded[i]:
            return f"{name} not sorted in UTF-16 order at line {i + 1}: {keys[i - 1]!r} >= {keys[i]!r}"
    return None


CHARS = load("chars.txt")
WORDS = load("words.txt")


def is_han(ch):
    name = unicodedata.name(ch, "")
    return "CJK" in name and "IDEOGRAPH" in name


def annotate(text):
    """Returns a list of (base, annotation|None, starts_word), as Kotlin does."""
    out, run = [], []

    def flush_run():
        i = 0
        while i < len(run):
            matched = False
            for length in range(min(MAX_WORD_LEN, len(run) - i), 1, -1):
                candidate = "".join(run[i:i + length])
                reading = WORDS.get(candidate)
                if reading is None:
                    continue
                syllables = reading.split(" ") if reading else None
                if syllables is not None and len(syllables) != length:
                    continue
                for k in range(length):
                    syllable = syllables[k] if syllables else CHARS.get(run[i + k])
                    out.append((run[i + k], syllable, k == 0))
                i += length
                matched = True
                break
            if not matched:
                out.append((run[i], CHARS.get(run[i]), True))
                i += 1
        run.clear()

    plain = []
    for ch in text:
        if is_han(ch):
            if plain:
                out.append(("".join(plain), None, True))
                plain.clear()
            run.append(ch)
        else:
            flush_run()
            plain.append(ch)
    flush_run()
    if plain:
        out.append(("".join(plain), None, True))
    return out


def reading_of(text):
    return " ".join(a for _, a, _ in annotate(text) if a)


def words_of(text):
    """Regroups tokens into the words the renderer will visually group."""
    groups = []
    for base, ann, starts in annotate(text):
        if ann is None:
            continue
        if starts or not groups:
            groups.append(base)
        else:
            groups[-1] += base
    return groups


CASES = [
    ("银行", "yín háng"),
    ("行为", "xíng wéi"),
    ("我在银行工作", "wǒ zài yín háng gōng zuò"),
    ("音乐", "yīn yuè"),
    ("快乐", "kuài lè"),
    ("觉得", "jué de"),
    ("睡觉", "shuì jiào"),
    ("还是", "hái shi"),
    ("还给", "huán gěi"),
    ("重复", "chóng fù"),
    ("重要", "zhòng yào"),
    ("长大", "zhǎng dà"),
    ("长城", "cháng chéng"),
    ("都市", "dū shì"),
    ("朋友", "péng you"),
    ("头发", "tóu fa"),
    ("发现", "fā xiàn"),
]


SEGMENT_CASES = [
    ("我在银行工作", ["我", "在", "银行", "工作"]),
    ("音乐让我快乐", ["音乐", "让", "我", "快乐"]),
    # Longer than MAX_WORD_LEN, so it segments into its parts - which is the
    # more useful grouping for a learner anyway.
    ("中华人民共和国", ["中华", "人民", "共和国"]),
]


def main():
    failures = []
    for text, expected in CASES:
        actual = reading_of(text)
        if actual != expected:
            failures.append((text, expected, actual))

    for text, expected in SEGMENT_CASES:
        actual = words_of(text)
        if actual != expected:
            failures.append((text, " / ".join(expected), " / ".join(actual)))

    # Structural invariant: every Han character yields exactly one token, and
    # nothing in the input is lost.
    sample = "我在银行工作，觉得还行。Hello 音乐 makes me 快乐！\n第二行。"
    tokens = annotate(sample)
    rebuilt = "".join(base for base, _, _ in tokens)
    if rebuilt != sample:
        failures.append(("round-trip", sample, rebuilt))

    han_count = sum(1 for ch in sample if is_han(ch))
    annotated = sum(1 for _, a, _ in tokens if a is not None)
    if han_count != annotated:
        failures.append(("coverage", f"{han_count} han", f"{annotated} annotated"))

    for name in ("chars.txt", "words.txt"):
        problem = check_sort_order(name)
        if problem:
            failures.append((name, "sorted for binary search", problem))

    total = len(CASES) + len(SEGMENT_CASES) + 4
    for text, expected, actual in failures:
        print(f"FAIL {text}\n  expected: {expected}\n  actual:   {actual}")

    print(f"\n{total - len(failures)}/{total} passed")
    print(f"\nsample -> {' '.join(f'{b}[{a}]' if a else b for b, a, _ in tokens)}")
    print(f"words  -> {' / '.join(words_of(sample))}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
