#!/usr/bin/env python3
"""Build the pinyin assets shipped in the APK.

Two sources:
  * Unihan kMandarin  -> the single most common reading for each character.
  * CC-CEDICT         -> word-level readings, which is how polyphones get resolved.

Every headword is kept, because the segmenter needs the full vocabulary to find
word boundaries — the renderer groups words visually and avoids breaking a line
mid-word. But a *reading* is only stored when it differs from the naive
per-character one: 中国 needs none (zhōng + guó is what you'd get anyway),
whereas 银行 does (háng, not xíng). Roughly 90% of entries need no reading,
which is what keeps the assets small.

Outputs (UTF-8, sorted in UTF-16 code-unit order so Kotlin's String.compareTo
can binary-search them directly):
  app/src/main/assets/chars.txt   char <TAB> pinyin
  app/src/main/assets/words.txt   word [<TAB> space-separated pinyin]
"""

import gzip
import io
import re
import sys
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

CEDICT_URL = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
UNIHAN_URL = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
CACHE = Path(__file__).resolve().parent / ".cache"

MAX_WORD_LEN = 6

TONE_ROWS = {
    "a": "āáǎàa",
    "o": "ōóǒòo",
    "e": "ēéěèe",
    "i": "īíǐìi",
    "u": "ūúǔùu",
    "ü": "ǖǘǚǜü",
}

SYLLABLE_RE = re.compile(r"^[a-zü]+[1-5]$")


def fetch(url: str, name: str) -> bytes:
    CACHE.mkdir(exist_ok=True)
    cached = CACHE / name
    if cached.exists():
        return cached.read_bytes()
    print(f"downloading {url}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=120) as resp:
        data = resp.read()
    cached.write_bytes(data)
    return data


def to_tone_marks(syllable: str) -> str | None:
    """`zhong1` -> `zhōng`, `lu:3` -> `lǚ`. None if it isn't a pinyin syllable."""
    s = syllable.replace("u:", "ü").replace("U:", "ü").lower()
    if not SYLLABLE_RE.match(s):
        return None
    tone = int(s[-1])
    body = s[:-1]
    if tone == 5:
        return body
    # Standard placement: a wins, then o, then e; otherwise the last vowel,
    # which gets `iu` -> iù and `ui` -> uì right.
    for vowel in ("a", "o", "e"):
        if vowel in body:
            idx = body.index(vowel)
            break
    else:
        idx = max((body.rfind(v) for v in TONE_ROWS), default=-1)
        if idx < 0:
            return None
    marked = TONE_ROWS[body[idx]][tone - 1]
    return body[:idx] + marked + body[idx + 1:]


def is_han(ch: str) -> bool:
    return "CJK" in unicodedata.name(ch, "") and "IDEOGRAPH" in unicodedata.name(ch, "")


def all_han(text: str) -> bool:
    return bool(text) and all(is_han(ch) for ch in text)


def load_chars() -> dict[str, str]:
    """Unihan kMandarin — already tone-marked, first value is the preferred one."""
    blob = fetch(UNIHAN_URL, "Unihan.zip")
    chars: dict[str, str] = {}
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        with zf.open("Unihan_Readings.txt") as fh:
            for raw in io.TextIOWrapper(fh, encoding="utf-8"):
                if not raw.startswith("U+"):
                    continue
                parts = raw.rstrip("\n").split("\t")
                if len(parts) != 3 or parts[1] != "kMandarin":
                    continue
                ch = chr(int(parts[0][2:], 16))
                chars[ch] = parts[2].split()[0]
    return chars


def load_words(chars: dict[str, str]) -> dict[str, str]:
    """Maps headword -> reading, or headword -> "" when the reading is implied."""
    blob = fetch(CEDICT_URL, "cedict.txt.gz")
    text = gzip.decompress(blob).decode("utf-8")
    words: dict[str, str] = {}
    with_reading, skipped_align, implied = 0, 0, 0

    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        head, _, rest = line.partition(" [")
        pinyin_raw, _, _ = rest.partition("] ")
        try:
            trad, simp = head.split(" ", 1)
        except ValueError:
            continue

        syllables = [to_tone_marks(s) for s in pinyin_raw.split()]
        if not syllables or any(s is None for s in syllables):
            continue
        reading = " ".join(syllables)  # type: ignore[arg-type]

        for form in {simp, trad}:
            if not all_han(form) or not (2 <= len(form) <= MAX_WORD_LEN):
                continue
            if len(form) != len(syllables):
                skipped_align += 1
                continue
            # CC-CEDICT is ordered roughly by prominence; keep the first
            # reading we see for a form rather than letting rare senses win.
            if form in words:
                continue
            naive = " ".join(chars.get(ch, "") for ch in form)
            if naive == reading:
                words[form] = ""  # boundary only; per-char readings suffice
                implied += 1
            else:
                words[form] = reading
                with_reading += 1

    print(
        f"words total={len(words)} with_reading={with_reading} implied={implied} "
        f"misaligned={skipped_align}",
        file=sys.stderr,
    )
    return words


def utf16_key(text: str) -> bytes:
    """Sort key matching Java/Kotlin String.compareTo.

    Kotlin compares UTF-16 code units, which for supplementary characters
    (surrogate pairs, e.g. CJK Ext-B) orders them *before* BMP characters above
    U+D800 — the opposite of Python's code-point ordering. Sorting on the
    UTF-16 bytes gives the app a file it can binary-search directly.
    """
    return text.encode("utf-16-be")


def write(path: Path, mapping: dict[str, str]) -> None:
    lines = []
    for key in sorted(mapping, key=utf16_key):
        value = mapping[key]
        lines.append(f"{key}\t{value}\n" if value else f"{key}\n")
    path.write_text("".join(lines), encoding="utf-8")
    print(f"{path.relative_to(ROOT)}: {len(mapping)} entries, {path.stat().st_size / 1024:.0f} KB")


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    chars = load_chars()
    words = load_words(chars)
    write(ASSETS / "chars.txt", chars)
    write(ASSETS / "words.txt", words)


if __name__ == "__main__":
    main()
