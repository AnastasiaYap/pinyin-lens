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


def _greedy(run, from_left):
    """One direction of greedy longest match; returns word lengths."""
    lengths = []
    cursor = 0 if from_left else len(run)
    while (cursor < len(run)) if from_left else (cursor > 0):
        remaining = len(run) - cursor if from_left else cursor
        taken = 1
        for length in range(min(MAX_WORD_LEN, remaining), 1, -1):
            start = cursor if from_left else cursor - length
            candidate = "".join(run[start:start + length])
            reading = WORDS.get(candidate)
            if reading is None:
                continue
            syllables = reading.split(" ") if reading else None
            if syllables is not None and len(syllables) != length:
                continue
            taken = length
            break
        if from_left:
            lengths.append(taken)
            cursor += taken
        else:
            lengths.insert(0, taken)
            cursor -= taken
    return lengths


def segment(run):
    """Bidirectional matching, mirroring PinyinEngine.segment."""
    forward = _greedy(run, True)
    backward = _greedy(run, False)
    if len(forward) != len(backward):
        return forward if len(forward) < len(backward) else backward
    return backward if backward.count(1) < forward.count(1) else forward


def annotate(text, third_tone=False):
    """Returns a list of (base, annotation|None, starts_word), as Kotlin does."""
    out, run = [], []

    def flush_run():
        i = 0
        for length in segment(run):
            word = "".join(run[i:i + length]) if length > 1 else None
            reading = WORDS.get(word) if word else None
            syllables = reading.split(" ") if reading else None
            for k in range(length):
                syllable = (
                    syllables[k] if syllables and k < len(syllables)
                    else CHARS.get(run[i + k])
                )
                out.append((run[i + k], syllable, k == 0))
            i += length
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
    return apply_sandhi(out, third_tone)


# --- tone sandhi, mirroring Sandhi.kt -------------------------------------

MARKS = {}
PLAIN = {}
ROWS = {"a": "āáǎà", "o": "ōóǒò", "e": "ēéěè",
        "i": "īíǐì", "u": "ūúǔù", "ü": "ǖǘǚǜ"}
for base, row in ROWS.items():
    for tone, ch in enumerate(row, start=1):
        MARKS[ch] = tone
        PLAIN[ch] = base

DATE_UNITS = {"月", "日", "号", "號"}
DIGITS = {"一","二","三","四","五","六","七","八","九","十",
          "两","兩","零","百","千","万","萬","亿","億"}


def tone_of(syllable):
    for ch in syllable or "":
        if ch in MARKS:
            return MARKS[ch]
    return 5


def strip_tone(syllable):
    return "".join(PLAIN.get(ch, ch) for ch in syllable)


def with_tone(syllable, tone):
    plain = strip_tone(syllable)
    if tone == 5:
        return plain
    index = -1
    for vowel in "aoe":
        if vowel in plain:
            index = plain.index(vowel)
            break
    if index < 0:
        for i in range(len(plain) - 1, -1, -1):
            if plain[i] in ROWS:
                index = i
                break
    if index < 0:
        return plain
    return plain[:index] + ROWS[plain[index]][tone - 1] + plain[index + 1:]


def apply_sandhi(tokens, third_tone):
    out = list(tokens)

    def next_tone(i):
        if i + 1 >= len(out):
            return None
        reading = out[i + 1][1]
        if reading is None:
            return None
        tone = tone_of(reading)
        if tone != 5:
            return tone
        citation = CHARS.get(out[i + 1][0])
        return tone_of(citation) if citation else tone

    def yi_takes_sandhi(i):
        previous = out[i - 1][0] if i > 0 else None
        if previous == "第" or previous in DIGITS:
            return False
        if i + 1 >= len(out):
            return False
        following = out[i + 1][0]
        return following not in DATE_UNITS and following not in DIGITS

    for i, (base, reading, starts) in enumerate(out):
        if reading is None:
            continue
        tone = next_tone(i)
        if tone is None:
            continue
        if base == "不" and tone == 4:
            out[i] = (base, with_tone(reading, 2), starts)
        elif base == "一" and yi_takes_sandhi(i):
            new = 2 if tone == 4 else (4 if 1 <= tone <= 3 else 0)
            if new:
                out[i] = (base, with_tone(reading, new), starts)

    if third_tone:
        for i in range(len(out) - 1):
            current, following = out[i], out[i + 1]
            if current[1] is None or following[1] is None or following[2]:
                continue
            if tone_of(current[1]) == 3 and tone_of(following[1]) == 3:
                out[i] = (current[0], with_tone(current[1], 2), current[2])
    return out


def reading_of(text, third_tone=False):
    return " ".join(a for _, a, _ in annotate(text, third_tone) if a)


def words_of(text, third_tone=False):
    """Regroups tokens into the words the renderer will visually group."""
    groups = []
    for base, ann, starts in annotate(text, third_tone):
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


# Sandhi: the readings the app shows are not the citation forms, and until
# these existed a regression here was invisible to CI.
SANDHI_CASES = [
    ("不是",   "bú shì",     False),
    ("不对",   "bú duì",     False),
    ("不好",   "bù hǎo",     False),
    ("一个",   "yí ge",      False),
    ("一天",   "yì tiān",    False),
    # 一 as a numeral rather than a count: no change.
    ("一月",   "yī yuè",     False),
    ("一号",   "yī hào",     False),
    ("一二三", "yī èr sān",  False),
    ("十一月", "shí yī yuè", False),
    ("第一课", "dì yī kè",   False),
    # Third tone is opt-in.
    ("你好",   "nǐ hǎo",     False),
    ("你好",   "ní hǎo",     True),
]

# Segmentation cases that greedy forward matching alone gets wrong.
BIDI_CASES = [
    ("北京大学生",  ["北京", "大学生"]),
    ("研究生命科学", ["研究", "生命科学"]),
]


def main():
    failures = []

    for text, expected, third in SANDHI_CASES:
        actual = reading_of(text, third)
        if actual != expected:
            failures.append((f"{text} (3rd tone={third})", expected, actual))

    for text, expected in BIDI_CASES:
        actual = words_of(text)
        if actual != expected:
            failures.append((text, " / ".join(expected), " / ".join(actual)))

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

    for name in ("chars.txt", "words.txt", "defs.txt"):
        problem = check_sort_order(name)
        if problem:
            failures.append((name, "sorted for binary search", problem))

    total = len(CASES) + len(SEGMENT_CASES) + len(SANDHI_CASES) + len(BIDI_CASES) + 4
    for text, expected, actual in failures:
        print(f"FAIL {text}\n  expected: {expected}\n  actual:   {actual}")

    print(f"\n{total - len(failures)}/{total} passed")
    print(f"\nsample -> {' '.join(f'{b}[{a}]' if a else b for b, a, _ in tokens)}")
    print(f"words  -> {' / '.join(words_of(sample))}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
