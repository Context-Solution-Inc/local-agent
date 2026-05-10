"""Unit tests for the upgraded JSON-array parser in generate.py.

Local instruct models emit a variety of preambles, code fences, and thinking
blocks. The parser must extract the JSON array regardless. Regressions here
will manifest as silent generation failures during M3 Phase B, so we lock
the contract in CI.
"""

from __future__ import annotations

import pytest

from classifier_training.generation.generate import _parse_array


def test_plain_array() -> None:
    assert _parse_array('[{"a": 1}]') == [{"a": 1}]


def test_code_fence_json() -> None:
    raw = "```json\n[{\"a\": 1}]\n```"
    assert _parse_array(raw) == [{"a": 1}]


def test_code_fence_bare() -> None:
    raw = "```\n[{\"a\": 1}]\n```"
    assert _parse_array(raw) == [{"a": 1}]


def test_thinking_block_stripped() -> None:
    raw = "<think>Let me consider...\nMore thinking.</think>\n\n[{\"a\": 1}]"
    assert _parse_array(raw) == [{"a": 1}]


def test_prose_around_array_sliced_off() -> None:
    raw = 'Sure, here are the examples:\n[{"a": 1}, {"a": 2}]\n\nLet me know!'
    assert _parse_array(raw) == [{"a": 1}, {"a": 2}]


def test_envelope_unwrapped() -> None:
    raw = '{"examples": [{"a": 1}]}'
    assert _parse_array(raw) == [{"a": 1}]


def test_combo_thinking_fence_prose() -> None:
    raw = (
        "<think>I should output an array.</think>\n"
        "Here we go:\n"
        "```json\n"
        '[{"a": 1}]\n'
        "```\n"
        "Done!"
    )
    assert _parse_array(raw) == [{"a": 1}]


def test_non_array_raises() -> None:
    with pytest.raises(ValueError, match="expected a JSON array"):
        _parse_array('{"foo": "bar"}')
