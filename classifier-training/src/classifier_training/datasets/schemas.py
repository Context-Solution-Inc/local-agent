"""Pydantic models matching CLASSIFIER_DATASETS.md sections 2.1 and 3.1.

These are the canonical shapes for the JSONL dataset files. Every example written
to disk MUST validate against the relevant model — `ct-validate` enforces this in
CI and labelers run it locally before pushing.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator


# =============================================================================
# Shared
# =============================================================================


class SplitName(str, Enum):
    """Per CLASSIFIER_DATASETS.md §2.2 / §3.2.

    The `regression` split is FROZEN once we ship — it never accepts new examples
    after launch and it gates classifier model updates.
    """

    TRAIN = "train"
    VAL = "val"
    TEST = "test"
    REGRESSION = "regression"


class Source(str, Enum):
    """Provenance of an example. Required for stratified analysis and to enforce
    the rule that real user data only enters via post-launch opt-in telemetry."""

    SYNTHETIC_V1 = "synthetic_v1"
    SYNTHETIC_V2 = "synthetic_v2"
    CROWDSOURCED = "crowdsourced"
    PRODUCTION_ANON = "production_anon"
    ADVERSARIAL = "adversarial"


# =============================================================================
# Pre-flight search classifier (CLASSIFIER_DATASETS.md §2)
# =============================================================================


class PreflightLabel(str, Enum):
    SEARCH_REQUIRED = "search_required"
    SEARCH_NOT_REQUIRED = "search_not_required"
    AMBIGUOUS = "ambiguous"


class PreflightConfidence(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class PreflightCategory(str, Enum):
    # Search-required (CLASSIFIER_DATASETS.md §2.3)
    SPORTS_RECENT = "sports_recent"
    SPORTS_UPCOMING = "sports_upcoming"
    MARKETS_CURRENT = "markets_current"
    WEATHER = "weather"
    NEWS_CURRENT = "news_current"
    PRICES_PRODUCTS = "prices_products"
    STATUS_RECENT = "status_recent"
    SCHEDULES_EVENTS = "schedules_events"
    # Search-not-required
    GENERAL_KNOWLEDGE = "general_knowledge"
    SETTLED_HISTORY = "settled_history"
    OPINION_REASONING = "opinion_reasoning"
    CODING_MATH = "coding_math"
    CREATIVE = "creative"
    PERSONAL_MEMORY = "personal_memory"
    META = "meta"
    # Ambiguous
    AMBIGUOUS = "ambiguous"


_SEARCH_REQUIRED_CATEGORIES = {
    PreflightCategory.SPORTS_RECENT,
    PreflightCategory.SPORTS_UPCOMING,
    PreflightCategory.MARKETS_CURRENT,
    PreflightCategory.WEATHER,
    PreflightCategory.NEWS_CURRENT,
    PreflightCategory.PRICES_PRODUCTS,
    PreflightCategory.STATUS_RECENT,
    PreflightCategory.SCHEDULES_EVENTS,
}
_SEARCH_NOT_REQUIRED_CATEGORIES = {
    PreflightCategory.GENERAL_KNOWLEDGE,
    PreflightCategory.SETTLED_HISTORY,
    PreflightCategory.OPINION_REASONING,
    PreflightCategory.CODING_MATH,
    PreflightCategory.CREATIVE,
    PreflightCategory.PERSONAL_MEMORY,
    PreflightCategory.META,
}


class PreflightRewriteHints(BaseModel):
    """Optional structured metadata used by the query-rewriting layer (M4 / WS-8).
    Not used at classifier training time."""

    model_config = ConfigDict(extra="forbid")

    needs_team_disambiguation: bool = False
    needs_location_disambiguation: bool = False
    temporal_resolution: str | None = None  # e.g. "previous_evening", "last_year_to_2025"
    needs_memory_lookup: bool = False


class PreflightExample(BaseModel):
    """One row of the pre-flight classifier dataset. Schema mirrors CLASSIFIER_DATASETS.md §2.1."""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=r"^preflight_\d{5,}$")
    query: str = Field(min_length=1, max_length=500)
    label: PreflightLabel
    confidence: PreflightConfidence
    category: PreflightCategory
    rationale: str = Field(min_length=1, max_length=500)
    rewrite_hints: PreflightRewriteHints | None = None
    pair_id: str | None = None  # Links adversarial pairs (CLASSIFIER_DATASETS.md §2.4)
    source: Source
    split: SplitName

    @field_validator("category")
    @classmethod
    def category_must_match_label(
        cls, v: PreflightCategory, info: object
    ) -> PreflightCategory:
        # Pydantic v2 passes a `ValidationInfo` with .data containing siblings.
        data = getattr(info, "data", {})
        label = data.get("label")
        if label is None:
            return v
        if label == PreflightLabel.SEARCH_REQUIRED and v not in _SEARCH_REQUIRED_CATEGORIES:
            raise ValueError(
                f"category {v} is not a search-required category but label is {label}"
            )
        if label == PreflightLabel.SEARCH_NOT_REQUIRED and v not in _SEARCH_NOT_REQUIRED_CATEGORIES:
            raise ValueError(
                f"category {v} is not a search-not-required category but label is {label}"
            )
        if label == PreflightLabel.AMBIGUOUS and v != PreflightCategory.AMBIGUOUS:
            raise ValueError(
                f"ambiguous label requires ambiguous category, got {v}"
            )
        return v

    @field_validator("confidence")
    @classmethod
    def ambiguous_must_be_medium(
        cls, v: PreflightConfidence, info: object
    ) -> PreflightConfidence:
        data = getattr(info, "data", {})
        if data.get("label") == PreflightLabel.AMBIGUOUS and v != PreflightConfidence.MEDIUM:
            raise ValueError(
                "ambiguous examples must be labeled medium confidence "
                "(CLASSIFIER_DATASETS.md §2.2)"
            )
        return v


# =============================================================================
# Memory extraction classifier (CLASSIFIER_DATASETS.md §3)
# =============================================================================


class MemoryCategory(str, Enum):
    PERSONAL_IDENTITY = "personal_identity"
    PREFERENCE = "preference"
    PROFESSIONAL = "professional"
    INTEREST = "interest"
    RELATIONSHIP = "relationship"
    TEMPORARY_CONTEXT = "temporary_context"


class MemoryStability(str, Enum):
    STABLE = "stable"
    EVOLVING = "evolving"
    EPHEMERAL = "ephemeral"


class MemoryConfidence(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class MemoryToExtract(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=300)
    category: MemoryCategory
    stability: MemoryStability
    confidence: MemoryConfidence
    expiration_iso_date: str | None = None  # required if category == TEMPORARY_CONTEXT

    @field_validator("expiration_iso_date")
    @classmethod
    def temporary_must_have_expiration(
        cls, v: str | None, info: object
    ) -> str | None:
        data = getattr(info, "data", {})
        if data.get("category") == MemoryCategory.TEMPORARY_CONTEXT and not v:
            raise ValueError(
                "temporary_context memories must include expiration_iso_date "
                "(CLASSIFIER_DATASETS.md §3.3)"
            )
        return v


class MemoryExtractionExample(BaseModel):
    """One row of the memory extraction classifier dataset. CLASSIFIER_DATASETS.md §3.1."""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(pattern=r"^memory_\d{5,}$")
    user_message: str = Field(min_length=1, max_length=2000)
    assistant_response: str = Field(min_length=1, max_length=4000)
    memories_to_extract: list[MemoryToExtract] = Field(default_factory=list)
    negative_extractions: list[MemoryToExtract] = Field(default_factory=list)
    rationale: str = Field(min_length=1, max_length=500)
    source: Source
    split: SplitName
    explicit_command: str | None = None  # e.g. "remember", "forget", null otherwise

    @field_validator("explicit_command")
    @classmethod
    def explicit_command_values(cls, v: str | None) -> str | None:
        if v is not None and v not in {"remember", "forget"}:
            raise ValueError("explicit_command must be 'remember', 'forget', or null")
        return v
