"""Generate the tokenizer canonical fixture used by the Kotlin
WordPieceTokenizer test suite.

This is the gate (CLAUDE.md inv. #13) that prevents silent tokenizer drift
between the training-time HuggingFace tokenizer and the on-device Kotlin
implementation. Each fixture row is a (text, expected_input_ids,
expected_attention_mask) triple. The Kotlin test asserts byte-exact match.

Run:
    classifier-training/.venv/bin/python \\
        classifier-training/scripts/generate_tokenizer_fixture.py

Output:
    classifier-training/tests/fixtures/tokenizer_canonical_inputs.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from transformers import AutoTokenizer

MAX_LENGTH = 128
MODEL_ID = "distilbert-base-uncased"

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_PATH = REPO_ROOT / "classifier-training" / "tests" / "fixtures" / "tokenizer_canonical_inputs.json"


# Each fixture is (id, text_a, text_b_or_none). text_b exercises the
# memory-style two-segment encoding with truncation_strategy="only_first".
SINGLE_INPUTS: list[tuple[str, str]] = [
    ("typical_sports_query", "did the eagles win last night"),
    ("weather_query", "what's the weather in toronto"),
    ("punctuation_question", "what's 7 + 8?"),
    ("contractions", "don't tell me what it isn't"),
    ("numbers_punctuation", "S&P 500 closed at 5,847.32"),
    ("unicode_diacritics", "what time is it in Tōkyō"),
    ("oov_heavy", "Cthulhu fhtagn ph'nglui mglw'nafh"),
    ("case_mix", "iPhone 16 vs iPhone 15"),
    ("all_caps", "WHO IS THE CEO OF NVIDIA"),
    # transformers's BasicTokenizer strips non-letter unicode — emoji become whitespace
    ("emoji_ignored", "is 😀 a happy face"),
    ("single_question_mark", "?"),
    ("url_in_query", "summarize https://example.com/foo/bar"),
    ("hyphenated_compound", "my-favorite-team"),
    ("leading_trailing_whitespace", "   what year is it   "),
    ("multi_sentence", "I love hiking. What's the weather like?"),
    ("acronym_with_periods", "U.S.A. independence date"),
    ("apostrophe_chains", "y'all'd've thought so"),
    ("single_token", "hello"),
    ("digits_only", "12345"),
    # 200+ words → forces truncation to 128. Mix of common and uncommon to
    # produce a realistic worst-case tokenization.
    ("long_truncates", " ".join(
        ["the quick brown fox jumps over the lazy dog"] * 30
        + ["pneumonoultramicroscopicsilicovolcanoconiosis"] * 5
    )),
]

# (id, text_a, text_b) — exercises memory-style two-segment encoding.
PAIR_INPUTS: list[tuple[str, str, str]] = [
    (
        "pair_short",
        "i live in toronto",
        "got it, i'll remember that you're in toronto.",
    ),
    (
        "pair_truncate_first",
        # text_a is intentionally very long so only_first truncation kicks in
        " ".join(["my favorite team is the philadelphia eagles"] * 40),
        "noted — eagles fan.",
    ),
]


def main() -> int:
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)

    fixtures: list[dict] = []

    for fixture_id, text in SINGLE_INPUTS:
        encoded = tokenizer(
            text,
            truncation=True,
            max_length=MAX_LENGTH,
            padding="max_length",
            return_tensors=None,
        )
        fixtures.append({
            "id": fixture_id,
            "kind": "single",
            "text_a": text,
            "text_b": None,
            "input_ids": encoded["input_ids"],
            "attention_mask": encoded["attention_mask"],
        })

    for fixture_id, text_a, text_b in PAIR_INPUTS:
        encoded = tokenizer(
            text_a,
            text_b,
            truncation="only_first",
            max_length=MAX_LENGTH,
            padding="max_length",
            return_tensors=None,
        )
        fixtures.append({
            "id": fixture_id,
            "kind": "pair",
            "text_a": text_a,
            "text_b": text_b,
            "input_ids": encoded["input_ids"],
            "attention_mask": encoded["attention_mask"],
        })

    # Assertion sanity
    for f in fixtures:
        assert len(f["input_ids"]) == MAX_LENGTH, f
        assert len(f["attention_mask"]) == MAX_LENGTH, f
        assert f["input_ids"][0] == tokenizer.cls_token_id, f
        assert tokenizer.sep_token_id in f["input_ids"], f

    payload = {
        "tokenizer": MODEL_ID,
        "max_length": MAX_LENGTH,
        "vocab_size": tokenizer.vocab_size,
        "special_tokens": {
            "cls": tokenizer.cls_token_id,
            "sep": tokenizer.sep_token_id,
            "pad": tokenizer.pad_token_id,
            "unk": tokenizer.unk_token_id,
        },
        "fixtures": fixtures,
    }

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False))
    print(f"wrote {len(fixtures)} fixtures to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
