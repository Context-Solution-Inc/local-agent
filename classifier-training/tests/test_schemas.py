"""Schema validation tests. Run via: pytest"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from classifier_training.datasets.schemas import (
    MemoryCategory,
    MemoryConfidence,
    MemoryExtractionExample,
    MemoryStability,
    MemoryToExtract,
    PreflightCategory,
    PreflightConfidence,
    PreflightExample,
    PreflightLabel,
    Source,
    SplitName,
)


# -------- Pre-flight --------


def test_preflight_minimal_valid() -> None:
    ex = PreflightExample(
        id="preflight_00042",
        query="did the Eagles pull it off last night",
        label=PreflightLabel.SEARCH_REQUIRED,
        confidence=PreflightConfidence.HIGH,
        category=PreflightCategory.SPORTS_RECENT,
        rationale="Asks about a specific recent sports outcome with implicit recency marker.",
        source=Source.SYNTHETIC_V1,
        split=SplitName.TRAIN,
    )
    assert ex.id == "preflight_00042"


def test_preflight_id_pattern_enforced() -> None:
    with pytest.raises(ValidationError):
        PreflightExample(
            id="bad_id",
            query="hi",
            label=PreflightLabel.SEARCH_NOT_REQUIRED,
            confidence=PreflightConfidence.HIGH,
            category=PreflightCategory.GENERAL_KNOWLEDGE,
            rationale="x",
            source=Source.SYNTHETIC_V1,
            split=SplitName.TRAIN,
        )


def test_preflight_search_required_label_rejects_not_required_category() -> None:
    with pytest.raises(ValidationError) as excinfo:
        PreflightExample(
            id="preflight_00001",
            query="who won the 1969 Super Bowl",
            label=PreflightLabel.SEARCH_REQUIRED,
            confidence=PreflightConfidence.HIGH,
            category=PreflightCategory.SETTLED_HISTORY,  # mismatch
            rationale="x",
            source=Source.SYNTHETIC_V1,
            split=SplitName.TRAIN,
        )
    assert "not a search-required category" in str(excinfo.value)


def test_preflight_ambiguous_must_be_medium_confidence() -> None:
    with pytest.raises(ValidationError):
        PreflightExample(
            id="preflight_00002",
            query="how does Tesla make money",
            label=PreflightLabel.AMBIGUOUS,
            confidence=PreflightConfidence.HIGH,  # invalid for ambiguous
            category=PreflightCategory.AMBIGUOUS,
            rationale="x",
            source=Source.SYNTHETIC_V1,
            split=SplitName.TRAIN,
        )


def test_preflight_extra_fields_forbidden() -> None:
    with pytest.raises(ValidationError):
        PreflightExample(
            id="preflight_00003",
            query="hi",
            label=PreflightLabel.SEARCH_NOT_REQUIRED,
            confidence=PreflightConfidence.HIGH,
            category=PreflightCategory.GENERAL_KNOWLEDGE,
            rationale="x",
            source=Source.SYNTHETIC_V1,
            split=SplitName.TRAIN,
            unexpected_field=True,  # type: ignore[call-arg]
        )


# -------- Memory extraction --------


def test_memory_minimal_valid_with_extraction() -> None:
    ex = MemoryExtractionExample(
        id="memory_00128",
        user_message="I've been a Philadelphia Eagles fan since I was a kid",
        assistant_response="That's a long-running loyalty.",
        memories_to_extract=[
            MemoryToExtract(
                text="User's favorite NFL team is the Philadelphia Eagles",
                category=MemoryCategory.PREFERENCE,
                stability=MemoryStability.STABLE,
                confidence=MemoryConfidence.HIGH,
            )
        ],
        rationale="Explicit, durable preference statement clearly volunteered by the user.",
        source=Source.SYNTHETIC_V1,
        split=SplitName.TRAIN,
    )
    assert len(ex.memories_to_extract) == 1


def test_memory_empty_extraction_valid() -> None:
    ex = MemoryExtractionExample(
        id="memory_00129",
        user_message="what's the weather in Toronto today",
        assistant_response="Let me check.",
        memories_to_extract=[],
        rationale="Transient query — Toronto might be the user's location but a single query is not strong evidence.",
        source=Source.SYNTHETIC_V1,
        split=SplitName.TRAIN,
    )
    assert ex.memories_to_extract == []


def test_memory_temporary_context_requires_expiration() -> None:
    with pytest.raises(ValidationError) as excinfo:
        MemoryToExtract(
            text="User is traveling to Tokyo next week",
            category=MemoryCategory.TEMPORARY_CONTEXT,
            stability=MemoryStability.EPHEMERAL,
            confidence=MemoryConfidence.HIGH,
            # expiration_iso_date missing
        )
    assert "expiration_iso_date" in str(excinfo.value)


def test_memory_explicit_command_must_be_known_value() -> None:
    with pytest.raises(ValidationError):
        MemoryExtractionExample(
            id="memory_00130",
            user_message="hi",
            assistant_response="hi",
            memories_to_extract=[],
            rationale="x",
            source=Source.SYNTHETIC_V1,
            split=SplitName.TRAIN,
            explicit_command="maybe",  # invalid
        )
