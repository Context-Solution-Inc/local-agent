"""Hand-authored adversarial pair prototypes for the pre-flight classifier.

Per CLASSIFIER_DATASETS.md §2.4 and M3_PLAN.md Phase C, the dataset must
include ≥800 examples in adversarial pairs covering five pair types. These
prototypes are the seeds — `ct-expand-pairs preflight` rephrases each side
N times to produce the full pair set.

Each pair shares a `pair_id` across all derived examples. At least one
example per pair must land in the `regression` split (forced by the driver).

Distribution: 16 prototypes per type, with framing_variants having 3 sides
(other types have 2). At 5 variants per side that yields:
  64 binary pairs × 2 sides × 5 = 640
  16 framing pairs × 3 sides × 5 = 240
  total ≈ 880 examples
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PrototypeSide:
    query: str
    label: str  # "search_required" | "search_not_required" | "ambiguous"
    category: str  # see PreflightCategory
    rationale: str
    confidence: str = "high"


@dataclass(frozen=True)
class PrototypePair:
    pair_id: str
    pair_type: str
    sides: tuple[PrototypeSide, ...]


# =============================================================================
# Type 1: settled_history_vs_recent (16)
# =============================================================================

SETTLED_VS_RECENT: tuple[PrototypePair, ...] = (
    PrototypePair(
        pair_id="superbowl_1969_vs_recent",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who won the 1969 Super Bowl",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical fact with explicit historical date well before model knowledge cutoff.",
            ),
            PrototypeSide(
                query="who won the Super Bowl last year",
                label="search_required",
                category="sports_recent",
                rationale="Relative recency marker forces a current-data lookup.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="world_series_1992_vs_recent",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who won the 1992 World Series",
                label="search_not_required",
                category="settled_history",
                rationale="Specific historical year fixes the answer to settled record.",
            ),
            PrototypeSide(
                query="who won the World Series this year",
                label="search_required",
                category="sports_recent",
                rationale="'This year' depends on current date and recent outcome.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="ww2_end_vs_ukraine_war",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="when did World War II end",
                label="search_not_required",
                category="settled_history",
                rationale="Closed historical event — date is settled and well-known.",
            ),
            PrototypeSide(
                query="is the war in Ukraine over",
                label="search_required",
                category="news_current",
                rationale="Ongoing geopolitical event whose status changes; needs current news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="nba_finals_1986_vs_now",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who won the 1986 NBA Finals",
                label="search_not_required",
                category="settled_history",
                rationale="Specific historical year.",
            ),
            PrototypeSide(
                query="who's leading in the NBA finals right now",
                label="search_required",
                category="sports_recent",
                rationale="'Right now' demands live current data.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="iphone_release_year_vs_latest",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="what year did Apple release the original iPhone",
                label="search_not_required",
                category="settled_history",
                rationale="Settled product launch date — 2007.",
            ),
            PrototypeSide(
                query="what's the latest iPhone model",
                label="search_required",
                category="prices_products",
                rationale="Current product lineup changes annually.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="tour_de_france_2003_vs_now",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who won the Tour de France in 2003",
                label="search_not_required",
                category="settled_history",
                rationale="Historical sports record fixed by date.",
            ),
            PrototypeSide(
                query="who's winning the Tour de France this year",
                label="search_required",
                category="sports_recent",
                rationale="In-progress race result; needs current news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="berlin_wall_vs_eu_news",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="when did the Berlin Wall fall",
                label="search_not_required",
                category="settled_history",
                rationale="Closed historical event — November 1989.",
            ),
            PrototypeSide(
                query="any news on EU expansion talks",
                label="search_required",
                category="news_current",
                rationale="Current diplomatic process; status changes.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="moon_first_vs_next",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who was the first person on the moon",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical fact — Neil Armstrong, 1969.",
            ),
            PrototypeSide(
                query="when's the next crewed moon landing scheduled",
                label="search_required",
                category="schedules_events",
                rationale="Programmatic schedule that has shifted multiple times; needs current source.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="ussr_dissolved_vs_russia_now",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="when did the Soviet Union dissolve",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical fact — 1991.",
            ),
            PrototypeSide(
                query="what's Russia doing in eastern Europe right now",
                label="search_required",
                category="news_current",
                rationale="Current geopolitical activity; needs live news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="champions_league_1995_vs_now",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who won the 1995 Champions League final",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical sports record — Ajax.",
            ),
            PrototypeSide(
                query="who's in the Champions League final this season",
                label="search_required",
                category="sports_recent",
                rationale="In-season tournament — current season's bracket changes weekly.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="titanic_year_vs_doc",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="what year did the Titanic sink",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical event — 1912.",
            ),
            PrototypeSide(
                query="is there a new Titanic documentary out",
                label="search_required",
                category="news_current",
                rationale="Current media releases need fresh data.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="nobel_2009_vs_recent",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who won the Nobel Peace Prize in 2009",
                label="search_not_required",
                category="settled_history",
                rationale="Specific historical year — Obama.",
            ),
            PrototypeSide(
                query="who won the Nobel Peace Prize this year",
                label="search_required",
                category="news_current",
                rationale="Annual award; current year's winner only knowable via current source.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="india_independence_vs_now",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="what year did India gain independence",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical fact — 1947.",
            ),
            PrototypeSide(
                query="what's happening in Indian politics right now",
                label="search_required",
                category="news_current",
                rationale="Current political activity changes daily.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="mona_lisa_vs_louvre_now",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="who painted the Mona Lisa",
                label="search_not_required",
                category="settled_history",
                rationale="Stable art history fact — Leonardo da Vinci.",
            ),
            PrototypeSide(
                query="what's the latest exhibit at the Louvre",
                label="search_required",
                category="schedules_events",
                rationale="Current museum programming.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="ww1_year_vs_middle_east",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="when did World War I begin",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical event — 1914.",
            ),
            PrototypeSide(
                query="what's the latest in the Middle East",
                label="search_required",
                category="news_current",
                rationale="Active geopolitical region; ongoing news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="apollo_11_vs_spacex_recent",
        pair_type="settled_history_vs_recent",
        sides=(
            PrototypeSide(
                query="when did Apollo 11 land on the moon",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical event — July 1969.",
            ),
            PrototypeSide(
                query="did SpaceX launch anything this week",
                label="search_required",
                category="news_current",
                rationale="Current launch cadence; needs recent news.",
            ),
        ),
    ),
)


# =============================================================================
# Type 2: generic_vs_current (16)
# =============================================================================

GENERIC_VS_CURRENT: tuple[PrototypePair, ...] = (
    PrototypePair(
        pair_id="sp500_def_vs_value",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is the S&P 500",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional question — the index itself is a stable concept.",
            ),
            PrototypeSide(
                query="what's the S&P 500 at right now",
                label="search_required",
                category="markets_current",
                rationale="Current index value — changes by the second.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="bitcoin_def_vs_price",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is bitcoin",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable definitional question.",
            ),
            PrototypeSide(
                query="what's the price of bitcoin today",
                label="search_required",
                category="markets_current",
                rationale="Live price needs current data.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="recession_def_vs_status",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is a recession",
                label="search_not_required",
                category="general_knowledge",
                rationale="Settled economic concept.",
            ),
            PrototypeSide(
                query="are we in a recession right now",
                label="search_required",
                category="news_current",
                rationale="Current economic state — needs latest indicators.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="fed_def_vs_recent_rate",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is the Federal Reserve",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable institutional definition.",
            ),
            PrototypeSide(
                query="did the Fed raise rates this week",
                label="search_required",
                category="news_current",
                rationale="Specific recent monetary policy decision.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="inflation_def_vs_rate",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is inflation",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional economic concept.",
            ),
            PrototypeSide(
                query="what's the current inflation rate",
                label="search_required",
                category="markets_current",
                rationale="Current rate published monthly; needs latest CPI data.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="unemployment_def_vs_rate",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what does the unemployment rate measure",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional macro indicator.",
            ),
            PrototypeSide(
                query="what's the unemployment rate this month",
                label="search_required",
                category="markets_current",
                rationale="Monthly published figure; needs current report.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="nasdaq_def_vs_today",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is the Nasdaq",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable definition of the exchange/index.",
            ),
            PrototypeSide(
                query="is the Nasdaq up today",
                label="search_required",
                category="markets_current",
                rationale="Today's market direction — live data only.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="gdp_def_vs_recent_report",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is GDP",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional macro concept.",
            ),
            PrototypeSide(
                query="did the latest GDP report come out",
                label="search_required",
                category="news_current",
                rationale="Quarterly release; need current news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="exchange_rate_def_vs_eurusd",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="how do exchange rates work",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable conceptual question.",
            ),
            PrototypeSide(
                query="what's the EUR/USD rate right now",
                label="search_required",
                category="markets_current",
                rationale="Live FX rate.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="oil_def_vs_price",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is crude oil",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional commodity question.",
            ),
            PrototypeSide(
                query="what's WTI crude trading at today",
                label="search_required",
                category="markets_current",
                rationale="Live commodity price.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="stocksplit_def_vs_nvda",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is a stock split",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable financial concept.",
            ),
            PrototypeSide(
                query="did NVDA do a stock split recently",
                label="search_required",
                category="news_current",
                rationale="Specific recent corporate action.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="tariff_def_vs_recent",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is a tariff",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable economic policy concept.",
            ),
            PrototypeSide(
                query="what tariffs did the administration just announce",
                label="search_required",
                category="news_current",
                rationale="Recent policy announcement.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="cpi_def_vs_recent_report",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is the consumer price index",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional macro indicator.",
            ),
            PrototypeSide(
                query="did the latest CPI report come out",
                label="search_required",
                category="news_current",
                rationale="Monthly release; needs current news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="gold_def_vs_price",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="why is gold considered a hedge",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable commentary on commodity behavior.",
            ),
            PrototypeSide(
                query="what's the price of gold today",
                label="search_required",
                category="markets_current",
                rationale="Live commodity price.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="treasury_def_vs_yield",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is a treasury bond",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable definition of a debt instrument.",
            ),
            PrototypeSide(
                query="what's the 10-year treasury yield right now",
                label="search_required",
                category="markets_current",
                rationale="Live rate.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="ipo_def_vs_recent",
        pair_type="generic_vs_current",
        sides=(
            PrototypeSide(
                query="what is an IPO",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable financial markets concept.",
            ),
            PrototypeSide(
                query="any big IPOs happen this week",
                label="search_required",
                category="news_current",
                rationale="Recent IPO activity needs current source.",
            ),
        ),
    ),
)


# =============================================================================
# Type 3: hypothetical_vs_factual (16)
# =============================================================================

HYPOTHETICAL_VS_FACTUAL: tuple[PrototypePair, ...] = (
    PrototypePair(
        pair_id="fed_hypo_vs_did_raise",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would happen if the Fed raised rates a full point",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Hypothetical scenario — answerable via reasoning, no current data needed.",
            ),
            PrototypeSide(
                query="did the Fed raise rates this week",
                label="search_required",
                category="news_current",
                rationale="Factual question about a recent specific decision.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="apple_buy_tesla_hypo_vs_actual",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would happen if Apple bought Tesla",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Hypothetical M&A scenario — analytical question.",
            ),
            PrototypeSide(
                query="is Apple actually buying Tesla",
                label="search_required",
                category="news_current",
                rationale="Factual question — needs current news to confirm or deny.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="ukraine_imagine_vs_now",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="imagine what would happen if Russia invaded Poland",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Explicit hypothetical framing.",
            ),
            PrototypeSide(
                query="is Russia advancing in Ukraine right now",
                label="search_required",
                category="news_current",
                rationale="Live military situation needs current source.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="recession_hypo_vs_today",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="how would a recession affect tech stocks",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Reasoning about market dynamics.",
            ),
            PrototypeSide(
                query="are tech stocks down today",
                label="search_required",
                category="markets_current",
                rationale="Current market direction.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="trump_run_hypo_vs_announce",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="if Trump runs again would he win",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Speculative scenario — answerable via reasoning.",
            ),
            PrototypeSide(
                query="did Trump announce a new campaign",
                label="search_required",
                category="news_current",
                rationale="Factual recent announcement.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="oil_hypo_200_vs_now",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would happen if oil hit $200 a barrel",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Hypothetical price scenario.",
            ),
            PrototypeSide(
                query="what's oil at right now",
                label="search_required",
                category="markets_current",
                rationale="Live price.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="twitter_hypo_vs_x_news",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="would Twitter still exist if Musk hadn't bought it",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Counterfactual — reasoning question.",
            ),
            PrototypeSide(
                query="any big news from X this week",
                label="search_required",
                category="news_current",
                rationale="Recent corporate news.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="knicks_could_vs_did_win",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="could the Knicks win the championship someday",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Speculative — rooted in season probability.",
            ),
            PrototypeSide(
                query="did the Knicks win last night",
                label="search_required",
                category="sports_recent",
                rationale="Specific recent game outcome.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="rates_10pct_hypo_vs_now",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would happen if interest rates went to 10%",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Hypothetical macro scenario.",
            ),
            PrototypeSide(
                query="what are mortgage rates this week",
                label="search_required",
                category="markets_current",
                rationale="Current rate environment.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="taiwan_hypo_vs_china_now",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="how would the world react if China invaded Taiwan",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Geopolitical hypothetical.",
            ),
            PrototypeSide(
                query="is China escalating in the Taiwan Strait",
                label="search_required",
                category="news_current",
                rationale="Live geopolitical activity.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="us_default_hypo_vs_debt_news",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would happen if the US defaulted on its debt",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Macro hypothetical.",
            ),
            PrototypeSide(
                query="any update on the debt ceiling negotiations",
                label="search_required",
                category="news_current",
                rationale="Current legislative process.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="eagles_wentz_hypo_vs_recent",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what if the Eagles never traded Carson Wentz",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Counterfactual sports analysis.",
            ),
            PrototypeSide(
                query="did the Eagles win last week",
                label="search_required",
                category="sports_recent",
                rationale="Specific recent game.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="advise_fed_hypo_vs_recent",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="if you were advising the Federal Reserve what would you do",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Roleplay/reasoning question.",
            ),
            PrototypeSide(
                query="what did the Fed say at the last meeting",
                label="search_required",
                category="news_current",
                rationale="Specific recent FOMC statement.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="tesla_stop_hypo_vs_cybertruck",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would happen if Tesla stopped making cars",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Hypothetical corporate strategy.",
            ),
            PrototypeSide(
                query="is Tesla still producing the Cybertruck",
                label="search_required",
                category="news_current",
                rationale="Specific current production fact.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="spacex_fail_hypo_vs_launch",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="if SpaceX failed who would lead Mars colonization",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Counterfactual industry question.",
            ),
            PrototypeSide(
                query="did SpaceX launch this week",
                label="search_required",
                category="news_current",
                rationale="Recent launch event.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="rate_5pct_hypo_vs_current_fed",
        pair_type="hypothetical_vs_factual",
        sides=(
            PrototypeSide(
                query="what would the impact be of a 5% Fed rate",
                label="search_not_required",
                category="opinion_reasoning",
                rationale="Hypothetical impact analysis.",
            ),
            PrototypeSide(
                query="what's the current Fed funds rate",
                label="search_required",
                category="markets_current",
                rationale="Current policy rate value.",
            ),
        ),
    ),
)


# =============================================================================
# Type 4: stable_vs_evolving (16)
# =============================================================================

STABLE_VS_EVOLVING: tuple[PrototypePair, ...] = (
    PrototypePair(
        pair_id="canada_pm_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first prime minister of Canada",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical fact — John A. Macdonald.",
            ),
            PrototypeSide(
                query="who is the prime minister of Canada",
                label="search_required",
                category="status_recent",
                rationale="Current officeholder — changes over time.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="us_president_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first US president",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — George Washington.",
            ),
            PrototypeSide(
                query="who is the current US president",
                label="search_required",
                category="status_recent",
                rationale="Sitting officeholder; changes every 4 years.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="microsoft_founder_vs_ceo",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded Microsoft",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Gates and Allen.",
            ),
            PrototypeSide(
                query="who is the CEO of Microsoft",
                label="search_required",
                category="status_recent",
                rationale="Executive role — could change.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="apple_founder_vs_ceo",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded Apple",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Jobs, Wozniak, Wayne.",
            ),
            PrototypeSide(
                query="who runs Apple now",
                label="search_required",
                category="status_recent",
                rationale="Current CEO subject to change.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="tesla_founder_vs_musk",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded Tesla",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Eberhard, Tarpenning (and Musk early on).",
            ),
            PrototypeSide(
                query="is Elon still CEO of Tesla",
                label="search_required",
                category="status_recent",
                rationale="Current executive status.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="superbowl_first_vs_reigning",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who won the first Super Bowl",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Green Bay Packers, 1967.",
            ),
            PrototypeSide(
                query="who's the reigning Super Bowl champion",
                label="search_required",
                category="sports_recent",
                rationale="Most recent winner; changes annually.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="liberal_party_founder_vs_leader",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded the Liberal Party of Canada",
                label="search_not_required",
                category="settled_history",
                rationale="Settled historical question.",
            ),
            PrototypeSide(
                query="who's the leader of the Liberal Party of Canada",
                label="search_required",
                category="status_recent",
                rationale="Current party leader; subject to change.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="un_secgen_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first UN secretary-general",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Trygve Lie.",
            ),
            PrototypeSide(
                query="who is the current UN secretary-general",
                label="search_required",
                category="status_recent",
                rationale="Current officeholder.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="pope_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first pope",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Saint Peter (per Catholic tradition).",
            ),
            PrototypeSide(
                query="who is the current pope",
                label="search_required",
                category="status_recent",
                rationale="Current officeholder; changes upon death/abdication.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="england_king_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first king of England",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — historians cite Æthelstan or Alfred.",
            ),
            PrototypeSide(
                query="who is the current king of England",
                label="search_required",
                category="status_recent",
                rationale="Current monarch.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="google_founder_vs_ceo",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded Google",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Page and Brin.",
            ),
            PrototypeSide(
                query="who is the CEO of Google now",
                label="search_required",
                category="status_recent",
                rationale="Current executive role.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="nba_commissioner_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first NBA commissioner",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Maurice Podoloff.",
            ),
            PrototypeSide(
                query="who is the NBA commissioner now",
                label="search_required",
                category="status_recent",
                rationale="Current officeholder.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="amazon_founder_vs_ceo",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded Amazon",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Jeff Bezos.",
            ),
            PrototypeSide(
                query="is Bezos still running Amazon",
                label="search_required",
                category="status_recent",
                rationale="Current executive status.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="fbi_director_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first FBI director",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — J. Edgar Hoover.",
            ),
            PrototypeSide(
                query="who is the FBI director right now",
                label="search_required",
                category="status_recent",
                rationale="Current officeholder.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="uk_pm_first_vs_current",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who was the first British prime minister",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Robert Walpole.",
            ),
            PrototypeSide(
                query="who is the British PM right now",
                label="search_required",
                category="status_recent",
                rationale="Current officeholder.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="spacex_founder_vs_ceo",
        pair_type="stable_vs_evolving",
        sides=(
            PrototypeSide(
                query="who founded SpaceX",
                label="search_not_required",
                category="settled_history",
                rationale="Settled — Elon Musk.",
            ),
            PrototypeSide(
                query="is Elon still running SpaceX",
                label="search_required",
                category="status_recent",
                rationale="Current executive status.",
            ),
        ),
    ),
)


# =============================================================================
# Type 5: framing_variants (16, 3 sides each)
# =============================================================================

FRAMING_VARIANTS: tuple[PrototypePair, ...] = (
    PrototypePair(
        pair_id="tesla_stock_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="Tesla stock",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare entity reference — could be definitional, current price, or news.",
                confidence="medium",
            ),
            PrototypeSide(
                query="Tesla stock price right now",
                label="search_required",
                category="markets_current",
                rationale="Explicit current-price framing.",
            ),
            PrototypeSide(
                query="how does Tesla stock work",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional / mechanism question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="bitcoin_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="bitcoin",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare entity reference; intent unclear.",
                confidence="medium",
            ),
            PrototypeSide(
                query="bitcoin price today",
                label="search_required",
                category="markets_current",
                rationale="Current value query.",
            ),
            PrototypeSide(
                query="what is bitcoin",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="gdp_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="GDP",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term; intent unclear.",
                confidence="medium",
            ),
            PrototypeSide(
                query="current US GDP",
                label="search_required",
                category="markets_current",
                rationale="Current value query.",
            ),
            PrototypeSide(
                query="what is GDP",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="fed_rate_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="Fed rate",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term — could be definition or current value.",
                confidence="medium",
            ),
            PrototypeSide(
                query="current Fed rate",
                label="search_required",
                category="markets_current",
                rationale="Explicit current value.",
            ),
            PrototypeSide(
                query="what is the Fed rate",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="nasdaq_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="Nasdaq",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare entity name.",
                confidence="medium",
            ),
            PrototypeSide(
                query="Nasdaq today",
                label="search_required",
                category="markets_current",
                rationale="Current value implied by 'today'.",
            ),
            PrototypeSide(
                query="what is the Nasdaq",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="sp500_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="S&P 500",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare entity name.",
                confidence="medium",
            ),
            PrototypeSide(
                query="S&P 500 today",
                label="search_required",
                category="markets_current",
                rationale="Current value query.",
            ),
            PrototypeSide(
                query="what is the S&P 500",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="iphone_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="iPhone",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare product name.",
                confidence="medium",
            ),
            PrototypeSide(
                query="latest iPhone model",
                label="search_required",
                category="prices_products",
                rationale="Current product lineup query.",
            ),
            PrototypeSide(
                query="how does the iPhone work",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional / mechanism question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="weather_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="weather",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term — needs location and intent.",
                confidence="medium",
            ),
            PrototypeSide(
                query="weather in Toronto today",
                label="search_required",
                category="weather",
                rationale="Specific location + day.",
            ),
            PrototypeSide(
                query="what causes weather",
                label="search_not_required",
                category="general_knowledge",
                rationale="Conceptual / scientific question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="eagles_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="Eagles",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare team name; intent unclear.",
                confidence="medium",
            ),
            PrototypeSide(
                query="Eagles game tonight",
                label="search_required",
                category="sports_upcoming",
                rationale="Specific game scheduling query.",
            ),
            PrototypeSide(
                query="who are the Philadelphia Eagles",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional/team-history question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="election_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="election",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term.",
                confidence="medium",
            ),
            PrototypeSide(
                query="election results today",
                label="search_required",
                category="news_current",
                rationale="Recent election outcomes.",
            ),
            PrototypeSide(
                query="how do US elections work",
                label="search_not_required",
                category="general_knowledge",
                rationale="Conceptual/civic question.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="inflation_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="inflation",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term.",
                confidence="medium",
            ),
            PrototypeSide(
                query="inflation today",
                label="search_required",
                category="markets_current",
                rationale="Current rate.",
            ),
            PrototypeSide(
                query="what is inflation",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="crypto_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="crypto",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term.",
                confidence="medium",
            ),
            PrototypeSide(
                query="crypto market today",
                label="search_required",
                category="markets_current",
                rationale="Current state of the crypto market.",
            ),
            PrototypeSide(
                query="what is crypto",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="apple_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="Apple",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare entity — could be company, fruit, or product line.",
                confidence="medium",
            ),
            PrototypeSide(
                query="Apple stock today",
                label="search_required",
                category="markets_current",
                rationale="Current price query.",
            ),
            PrototypeSide(
                query="what does Apple make",
                label="search_not_required",
                category="general_knowledge",
                rationale="Stable corporate fact.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="openai_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="OpenAI",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare entity.",
                confidence="medium",
            ),
            PrototypeSide(
                query="latest OpenAI news",
                label="search_required",
                category="news_current",
                rationale="Current company news.",
            ),
            PrototypeSide(
                query="what is OpenAI",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional / company-overview.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="tour_de_france_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="Tour de France",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare event name.",
                confidence="medium",
            ),
            PrototypeSide(
                query="Tour de France winner today",
                label="search_required",
                category="sports_recent",
                rationale="Current/recent winner query.",
            ),
            PrototypeSide(
                query="what is the Tour de France",
                label="search_not_required",
                category="general_knowledge",
                rationale="Definitional / event-overview.",
            ),
        ),
    ),
    PrototypePair(
        pair_id="interest_rates_framing",
        pair_type="framing_variants",
        sides=(
            PrototypeSide(
                query="interest rates",
                label="ambiguous",
                category="ambiguous",
                rationale="Bare term.",
                confidence="medium",
            ),
            PrototypeSide(
                query="interest rates today",
                label="search_required",
                category="markets_current",
                rationale="Current rate environment.",
            ),
            PrototypeSide(
                query="how do interest rates work",
                label="search_not_required",
                category="general_knowledge",
                rationale="Conceptual question.",
            ),
        ),
    ),
)


# =============================================================================
# Aggregate
# =============================================================================

ALL_PROTOTYPES: tuple[PrototypePair, ...] = (
    *SETTLED_VS_RECENT,
    *GENERIC_VS_CURRENT,
    *HYPOTHETICAL_VS_FACTUAL,
    *STABLE_VS_EVOLVING,
    *FRAMING_VARIANTS,
)


def expected_example_count(variants_per_side: int) -> int:
    return sum(len(p.sides) * variants_per_side for p in ALL_PROTOTYPES)
