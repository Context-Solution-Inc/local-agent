"""Unit tests for dedup canonicalization + bucket key.

The dedup is a hot path in the fill driver — duplicates that slip through
inflate cell counts and bias the dataset. Lock the contract here.
"""

from __future__ import annotations

import pytest

from classifier_training.datasets.dedup import _bucket_key, _canonicalize


def test_canonicalize_lowercases() -> None:
    assert _canonicalize("Did the Eagles WIN") == "did the eagles win"


def test_canonicalize_strips_punctuation() -> None:
    assert _canonicalize("how is Apple doing?!") == "how is apple doing"


def test_canonicalize_collapses_whitespace() -> None:
    assert _canonicalize("hey   so  whats up") == "hey so whats up"


def test_canonicalize_handles_unicode() -> None:
    assert _canonicalize("hey, what's up?") == "hey what s up"


def test_bucket_key_preflight_uses_category() -> None:
    row = {"id": "preflight_00042", "category": "sports_recent"}
    assert _bucket_key(row) == "preflight:sports_recent"


def test_bucket_key_memory_empty() -> None:
    row = {"id": "memory_00100", "memories_to_extract": []}
    assert _bucket_key(row) == "memory:empty"


def test_bucket_key_memory_one() -> None:
    row = {"id": "memory_00200", "memories_to_extract": [{"text": "x"}]}
    assert _bucket_key(row) == "memory:one"


def test_bucket_key_memory_multi() -> None:
    row = {"id": "memory_00300", "memories_to_extract": [{"text": "x"}, {"text": "y"}]}
    assert _bucket_key(row) == "memory:multi"


def test_canonicalize_paraphrase_collision() -> None:
    """Different surface forms with the same canonical → exact-match dedup catches them."""
    a = "Did   the   Eagles win?"
    b = "did the eagles win"
    assert _canonicalize(a) == _canonicalize(b) == "did the eagles win"
