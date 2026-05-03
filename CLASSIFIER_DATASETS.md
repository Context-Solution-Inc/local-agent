# Classifier Training Dataset Specification

**Document version:** 1.0
**Status:** Draft
**Last updated:** May 3, 2026
**Companion to:** PRD.md (sections 3.2.1, 3.2.4)

---

## 1. Overview

This document specifies the labeled training and evaluation datasets for the two on-device classifiers used by the assistant: the **pre-flight search classifier** and the **memory extraction classifier**. Both classifiers share architectural choices (small encoder model, ~30–80M params, fine-tuned with a task-specific head) but require distinct datasets with different schemas, label distributions, and quality bars.

The datasets must be developed before classifier training and maintained as living artifacts post-launch. Quality of these datasets is the single largest determinant of classifier accuracy — and classifier accuracy directly determines the assistant's ability to avoid confidently wrong answers about current events.

---

## 2. Pre-flight search classifier dataset

### 2.1 Schema

Each training example is a JSON object with the following fields:

```json
{
  "id": "preflight_00042",
  "query": "did the Eagles pull it off last night",
  "label": "search_required",
  "confidence": "high",
  "category": "sports_recent",
  "rationale": "Asks about a specific recent sports outcome with implicit recency marker 'last night'",
  "rewrite_hints": {
    "needs_team_disambiguation": true,
    "temporal_resolution": "previous_evening"
  },
  "source": "synthetic_v1",
  "split": "train"
}
```

**Field definitions:**

`id` — stable identifier for the example, prefixed by classifier name and zero-padded sequence.

`query` — the natural-language user query as it would arrive at the agent. Must be realistic in tone, capitalization, and punctuation; not all queries are well-formed grammatical English.

`label` — one of three values: `search_required` (the query needs fresh web data to answer correctly), `search_not_required` (the model can answer from training data), or `ambiguous` (genuine middle-ground cases used to validate the confidence-band routing).

`confidence` — one of `high`, `medium`, `low`. Combined with `label`, this drives the classifier's training targets and threshold validation. High-confidence `search_required` examples are the ones that should clear the 0.85 threshold; high-confidence `search_not_required` examples should fall below 0.15.

`category` — one of the categories listed in section 2.3, used for stratified sampling and per-category accuracy reporting.

`rationale` — short human-readable justification for the label. Used during dataset review and for spot-checking labeler consistency. Not used at training time.

`rewrite_hints` — optional structured metadata that flags whether the query needs rewriting before being sent to Brave (e.g., resolving "my team" via memory, expanding "last night" to a date). Used to develop the query rewriting layer, not the classifier itself.

`source` — provenance: `synthetic_v1` (LLM-generated and human-reviewed), `synthetic_v2` (later iterations), `crowdsourced` (paid labeler-authored), `production_anon` (anonymized real queries from opt-in telemetry, post-launch only), `adversarial` (deliberately constructed edge cases).

`split` — one of `train`, `val`, `test`, `regression`. The `regression` split is a frozen subset that gates classifier model updates from shipping.

### 2.2 Size and distribution

**Initial v1 dataset:** 12,000 examples total.

| Split | Count | Purpose |
|---|---|---|
| Train | 9,000 | Classifier fine-tuning |
| Val | 1,500 | Hyperparameter selection, early stopping |
| Test | 1,000 | Held-out evaluation, reported in metrics |
| Regression | 500 | Frozen gate for model updates; never modified post-launch |

**Label distribution targets:**

| Label | Proportion | Rationale |
|---|---|---|
| search_required | 40% | Over-represented vs. real traffic to ensure recall on the high-stakes class |
| search_not_required | 45% | Includes definitions, opinions, coding, math, settled history |
| ambiguous | 15% | Critical for validating confidence-band routing |

**Confidence distribution within each label:**

For `search_required` and `search_not_required`, target 70% high / 25% medium / 5% low confidence. The low-confidence examples are deliberately included to teach the classifier to express appropriate uncertainty rather than always producing extreme scores. For `ambiguous`, all examples are labeled medium confidence by definition.

### 2.3 Categories

The classifier should learn category-conditional patterns. Each example must be tagged with exactly one primary category from the following taxonomy:

**Search-required categories:**

`sports_recent` — outcomes, scores, standings for any sport within the last 18 months. Examples: "who won the Super Bowl last year," "did Liverpool win," "what's the Lakers record this season."

`sports_upcoming` — schedules, fixtures, kickoff times. Examples: "when do the Knicks play next," "what time is the F1 race today."

`markets_current` — stock prices, indices, crypto, commodities, exchange rates as of a recent date. Examples: "how did the markets do today," "what's NVDA at," "is Bitcoin up."

`weather` — current conditions or forecasts. Examples: "is it going to rain in Toronto," "weather in Tokyo this week."

`news_current` — recent events, ongoing stories. Examples: "what's happening with the election," "any news on the merger."

`prices_products` — current product availability and pricing. Examples: "how much is a PS5 right now," "is the new iPhone in stock."

`status_recent` — the current status of people, organizations, projects. Examples: "is Sam Altman still CEO of OpenAI," "did the bill pass."

`schedules_events` — date-bound scheduled events. Examples: "when is WWDC this year," "what time does the eclipse start."

**Search-not-required categories:**

`general_knowledge` — definitions, explanations, scientific concepts. Examples: "what is photosynthesis," "explain how DNS works."

`settled_history` — historical events with explicit historical context or that occurred well before the model's knowledge cutoff. Examples: "who won the 1969 Super Bowl," "when did WWII end."

`opinion_reasoning` — subjective questions, advice, reasoning. Examples: "should I learn Rust or Go," "what's the best way to handle a breakup."

`coding_math` — programming and math questions. Examples: "how do I reverse a string in Python," "solve 2x + 5 = 17."

`creative` — creative writing, brainstorming. Examples: "write me a haiku about coffee," "give me ideas for a birthday party."

`personal_memory` — questions about prior conversations or stored memories (handled by memory retrieval, not search). Examples: "what did I tell you about my project," "remind me what we discussed."

`meta` — questions about the assistant itself. Examples: "what can you do," "are you ChatGPT."

**Ambiguous category:**

`ambiguous` — genuine middle ground. Examples: "what's the best Italian restaurant in Toronto" (could be answered from training, but freshness helps), "how does Tesla make money" (general business model is stable, but specifics change).

### 2.4 Adversarial pairs

A core requirement: at least 800 examples (about 7% of the dataset) must come in **adversarial pairs** that test the classifier's ability to distinguish near-identical surface forms with different correct labels. These are the hardest examples and the ones most likely to surface confidently-wrong answers if the classifier fails on them.

**Required pair types:**

*Settled history vs. recent events:* "who won the 1969 Super Bowl" (settled, no search) vs. "who won the Super Bowl last year" (recent, search required).

*Generic knowledge vs. current state:* "what is the S&P 500" (definition, no search) vs. "what's the S&P 500 at" (current value, search required).

*Hypothetical vs. factual:* "what would happen if the Fed raised rates" (reasoning, no search) vs. "did the Fed raise rates this week" (factual recent event, search required).

*Stable vs. evolving facts:* "who is the prime minister of Canada" (search to verify current officeholder, since this changes) vs. "who was the first prime minister of Canada" (settled history).

*Question framing variants:* "Tesla stock" alone (ambiguous), "Tesla stock price" (search required), "how does Tesla stock work" (general knowledge).

Each adversarial pair must be tagged with a `pair_id` field linking the related examples, and at least one example from every pair must appear in the regression split.

### 2.5 Naturalistic phrasings

Real users do not type like search queries. The dataset must include substantial coverage of:

Casual phrasings ("did my guys win," "is the market up or what"), incomplete sentences ("Eagles game last night?"), typos and informal punctuation ("whats happening with crypto rn"), context-dependent references requiring memory ("did they win," "how's it going for them"), questions buried in conversational filler ("hey so I was wondering, like, did the Eagles end up winning"), and multi-question turns ("did the Eagles win and what's the weather like").

Target: at least 30% of training examples must be in naturalistic (non-search-engine-style) phrasings. This is the gap that pure rule-based matching cannot close and is the primary justification for using a learned classifier.

### 2.6 Sourcing strategy

**Phase 1 (pre-launch, ~10,000 examples):** Synthetic generation via Gemma 4 itself or a larger frontier model, with a structured generation prompt that produces examples conforming to the schema. Each generated example is reviewed by a human labeler who can accept, reject, or relabel. Reject rate is expected to be 30–40% on the first pass.

**Phase 2 (pre-launch, ~2,000 examples):** Adversarial pair construction by a small team of in-house labelers familiar with the failure modes. These are the highest-quality examples and require careful human authorship.

**Phase 3 (post-launch):** Anonymized real queries from opt-in telemetry, labeled by humans. Target 500–1,000 new examples per month, with new examples flowing into train/val splits but never into the regression split.

### 2.7 Inter-labeler agreement

All examples not generated by a single in-house authority must be labeled by at least two independent labelers. The acceptance criterion is full agreement on `label` and within-one-step agreement on `confidence` (e.g., high/medium is acceptable, high/low requires adjudication). Agreement rates must be tracked and reported; below 85% Cohen's kappa indicates the labeling guidelines need clarification.

---

## 3. Memory extraction classifier dataset

### 3.1 Schema

Each example represents a complete user turn (user message + assistant response) and labels what, if anything, should be extracted as memory.

```json
{
  "id": "memory_00128",
  "user_message": "I've been a Philadelphia Eagles fan since I was a kid",
  "assistant_response": "That's a long-running loyalty! Eagles fandom can be character-building. Anything specific you wanted to talk about?",
  "memories_to_extract": [
    {
      "text": "User's favorite NFL team is the Philadelphia Eagles",
      "category": "preference",
      "stability": "stable",
      "confidence": "high"
    }
  ],
  "negative_extractions": [],
  "rationale": "Explicit, durable preference statement clearly volunteered by the user.",
  "source": "synthetic_v1",
  "split": "train"
}
```

**Field definitions:**

`user_message` and `assistant_response` — the full text of the exchange. The classifier sees both because some memories are confirmed or contextualized by the assistant's reply.

`memories_to_extract` — an array (possibly empty) of memory objects. Each contains:
  - `text`: the canonical natural-language memory as it should be stored
  - `category`: one of `personal_identity`, `preference`, `professional`, `interest`, `relationship`, `temporary_context`
  - `stability`: `stable` (durable facts about the user), `evolving` (likely to change, e.g., job title), `ephemeral` (time-bound, e.g., upcoming travel)
  - `confidence`: how confidently this should be extracted

`negative_extractions` — an array of memories the classifier might be tempted to extract but should NOT. Used for hard-negative training and for catching specific failure modes (e.g., extracting search queries as preferences).

`rationale` — labeler's justification.

### 3.2 Size and distribution

**Initial v1 dataset:** 8,000 examples total.

| Split | Count | Purpose |
|---|---|---|
| Train | 6,000 | Classifier fine-tuning |
| Val | 1,000 | Hyperparameter selection |
| Test | 600 | Held-out evaluation |
| Regression | 400 | Frozen update gate |

**Memory-presence distribution:**

| Examples with... | Proportion | Notes |
|---|---|---|
| 0 memories to extract | 60% | The common case — most turns don't produce memories |
| 1 memory | 28% | Single-fact disclosures |
| 2+ memories | 12% | Information-dense turns |

The 60% empty rate matters: the classifier must learn that extracting nothing is the correct behavior most of the time. Over-extraction is the dominant failure mode if this ratio is wrong.

### 3.3 Categories

`personal_identity` — name, location, age, family. Examples: "User's name is Sarah," "User lives in Toronto."

`preference` — likes, dislikes, favorites. Examples: "User prefers dark roast coffee," "User's favorite NFL team is the Eagles."

`professional` — job, employer, professional context. Examples: "User is a software engineer," "User works on mobile apps."

`interest` — hobbies, topics they engage with regularly. Examples: "User follows F1 racing," "User is learning Spanish."

`relationship` — significant people in the user's life. Examples: "User has a dog named Rex," "User's partner is named Alex."

`temporary_context` — time-bound facts. Examples: "User is traveling to Tokyo next week," "User has a deadline on Friday." These memories must include an `expiration` field.

### 3.4 Negative examples

Approximately 60% of examples have empty `memories_to_extract`. These come in several flavors, each of which must be represented:

*Transient queries:* "what's the weather in Toronto" — Toronto might be the user's location, but a single query is not strong evidence.

*Hypotheticals:* "imagine I lived in Paris" — explicit hypothetical framing.

*Other people's facts:* "my friend John is a doctor" — facts about third parties are not user memories.

*Search queries phrased as statements:* "Tesla stock is up" — this is a fact-check or news query, not a user preference.

*Repeated information:* exchanges where the user states something already in memory. The classifier should not produce duplicate memories (deduplication is also done at storage time, but the classifier should learn to skip these).

### 3.5 Hard cases requiring careful labeling

**Implicit vs. explicit preferences:** "I always order an Americano" should produce a coffee preference memory; "I had an Americano this morning" should not (single occurrence, ambiguous as preference).

**Temporary vs. stable:** "I'm working on a mobile app" — could be the user's stable professional context or a one-off project. Default labeling rule: extract as stable unless context clearly indicates otherwise; stale memories will be evicted later if disconfirmed.

**Sensitive facts:** the dataset must include examples where users disclose health, financial, or relationship details. The default labeling rule is to extract these only if the user appears to be sharing them as durable context (not as a transient question). Labelers must err on the side of NOT extracting in ambiguous cases for sensitive categories.

**Explicit forget commands:** "actually, forget I said that" or "don't remember this" must produce zero extractions and ideally trigger deletion of the prior memory. The dataset must include at least 200 examples of explicit forget commands.

**Explicit remember commands:** "remember that I'm allergic to peanuts" must produce a high-confidence extraction even if the structure would otherwise be ambiguous. At least 200 examples.

### 3.6 Sourcing strategy

Same three-phase approach as the pre-flight dataset (synthetic generation, in-house adversarial authoring, post-launch real data). Memory extraction is harder to synthesize naturalistically than search classification because it requires realistic multi-turn-style exchanges. Budget more in-house labeler time for this dataset.

---

## 4. Shared dataset infrastructure

### 4.1 Storage and versioning

Both datasets are versioned and stored as JSONL files in a dedicated repository, with semantic versioning (e.g., `preflight_v1.2.0.jsonl`). The repository tracks dataset changes via Git history. Each classifier training run records the exact dataset version it was trained on, and the trained model artifact embeds this version string in its metadata.

### 4.2 Quality assurance

A standing 5% sample of each dataset is re-labeled by a different labeler quarterly. Drift in agreement rates triggers a labeling guideline review. Any example flagged by users post-launch as producing wrong classification (via thumbs-down on a response) is added to a review queue for potential inclusion in adversarial sets.

### 4.3 Privacy

Real user data may only enter datasets via the explicit opt-in telemetry channel described in PRD section 3.2.1, with full anonymization (no user identifiers, no surrounding conversation context, no memory content). Even with anonymization, queries that contain personally identifying information (detected via regex pre-filter) are dropped. Memory extraction examples never include real user data — synthetic-only — because the surrounding conversational context for memory extraction is too rich to anonymize safely.

### 4.4 Labeling guidelines

A separate `LABELING_GUIDELINES.md` document must be maintained alongside the dataset, with worked examples for every edge case. This document is the source of truth for labeler training and adjudication. It must be updated whenever a new failure mode is discovered post-launch.
