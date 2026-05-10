"""Hand-authored memory-extraction hard-case prototypes.

Per CLASSIFIER_DATASETS.md §3.5 and M3_PLAN.md Phase C, the memory dataset
must include explicit hard-case coverage:
  - implicit_vs_explicit_preference (~80 pairs)
  - temporary_vs_stable (~80 pairs)
  - sensitive (~50 examples)

These prototypes seed `ct-expand-pairs memory` which produces ~5 rephrasings
per side. Pair structure (where applicable) shares a `pair_id` across
all variants. Sensitive examples are individual, not paired — each is a
unique disclosure scenario.

`forget_command` / `remember_command` minimums are already exceeded by
Phase B generation (527 forget, 737 remember vs spec ≥200 each), so they
are not authored here.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class MemoryFact:
    text: str
    category: str  # see MemoryCategory
    stability: str  # stable | evolving | ephemeral
    confidence: str = "high"
    expiration_iso_date: str | None = None


@dataclass(frozen=True)
class MemoryProto:
    """One prototype memory example with its hand-curated extraction labels."""
    user_message: str
    assistant_response: str
    rationale: str
    memories_to_extract: tuple[MemoryFact, ...] = field(default_factory=tuple)
    negative_extractions: tuple[MemoryFact, ...] = field(default_factory=tuple)
    explicit_command: str | None = None  # "remember" | "forget" | None


@dataclass(frozen=True)
class MemoryProtoPair:
    pair_id: str
    pair_type: str  # implicit_vs_explicit_preference | temporary_vs_stable | sensitive
    sides: tuple[MemoryProto, ...]


# =============================================================================
# Type 1: implicit_vs_explicit_preference (16 pairs)
# =============================================================================
# side[0]: durable preference — extract.
# side[1]: one-off occurrence — do NOT extract; capture as negative.
# =============================================================================

IMPLICIT_VS_EXPLICIT: tuple[MemoryProtoPair, ...] = (
    MemoryProtoPair(
        pair_id="coffee_americano",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I always order an Americano when I get coffee.",
                assistant_response="Solid choice — clean and strong.",
                rationale="'Always' marks a durable coffee preference.",
                memories_to_extract=(
                    MemoryFact(text="User prefers Americanos for coffee", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I had an Americano this morning.",
                assistant_response="Nice — what kind of beans was it made with?",
                rationale="Single occurrence is not a durable preference.",
                negative_extractions=(
                    MemoryFact(text="User prefers Americanos", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="brewing_french_press",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="My favorite way to brew coffee is with a French press.",
                assistant_response="Great extraction method.",
                rationale="Explicit favorite — durable preference.",
                memories_to_extract=(
                    MemoryFact(text="User prefers French press brewing", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I tried using a French press today and it was alright.",
                assistant_response="Did you experiment with grind size?",
                rationale="One trial — not enough signal for a preference.",
                negative_extractions=(
                    MemoryFact(text="User prefers French press brewing", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="music_jazz",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="Jazz is my go-to genre — I listen to it most days.",
                assistant_response="Any favorite artists?",
                rationale="Frequent listening + explicit go-to language.",
                memories_to_extract=(
                    MemoryFact(text="User's favorite music genre is jazz", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I listened to some jazz on the drive home today.",
                assistant_response="Anything specific?",
                rationale="Single listening session ≠ preference.",
                negative_extractions=(
                    MemoryFact(text="User likes jazz music", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="team_eagles",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I've been an Eagles fan since I was a kid.",
                assistant_response="That's a long-running loyalty.",
                rationale="Explicit, durable team allegiance.",
                memories_to_extract=(
                    MemoryFact(text="User's favorite NFL team is the Philadelphia Eagles", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I caught the Eagles game on TV last night.",
                assistant_response="Anything good?",
                rationale="Watching one game doesn't establish team allegiance.",
                negative_extractions=(
                    MemoryFact(text="User is an Eagles fan", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="movies_comedies",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I really only watch comedies — drama isn't my thing.",
                assistant_response="What's a recent one you enjoyed?",
                rationale="Explicit genre preference + exclusion.",
                memories_to_extract=(
                    MemoryFact(text="User prefers comedies and dislikes drama films", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I watched a comedy on the plane yesterday.",
                assistant_response="Was it any good?",
                rationale="Single viewing, neutral context.",
                negative_extractions=(
                    MemoryFact(text="User prefers comedies", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="book_dune",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="Dune is my all-time favorite novel — I re-read it every year.",
                assistant_response="What draws you back to it?",
                rationale="Explicit favorite + recurring re-read.",
                memories_to_extract=(
                    MemoryFact(text="User's favorite novel is Dune", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I started reading Dune last week.",
                assistant_response="Hope you're enjoying it.",
                rationale="One-time reading attempt, no preference signal.",
                negative_extractions=(
                    MemoryFact(text="User likes Dune", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="color_blue",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="Blue is and always has been my favorite color.",
                assistant_response="Any particular shade?",
                rationale="Explicit, lifelong preference.",
                memories_to_extract=(
                    MemoryFact(text="User's favorite color is blue", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm wearing a blue shirt today.",
                assistant_response="Sounds sharp.",
                rationale="Single outfit choice tells us nothing about preference.",
                negative_extractions=(
                    MemoryFact(text="User's favorite color is blue", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="cuisine_thai",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I love Thai food — it's what I order whenever we get takeout.",
                assistant_response="What's your usual order?",
                rationale="Explicit love + repeated ordering pattern.",
                memories_to_extract=(
                    MemoryFact(text="User loves Thai food and orders it frequently", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="We had Thai food for dinner last night.",
                assistant_response="What did you get?",
                rationale="One meal, no preference inference.",
                negative_extractions=(
                    MemoryFact(text="User likes Thai food", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="drink_bourbon",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="Bourbon is my drink of choice — I keep a bottle of Buffalo Trace at home.",
                assistant_response="Solid pick.",
                rationale="Explicit drink-of-choice + ownership.",
                memories_to_extract=(
                    MemoryFact(text="User prefers bourbon (specifically Buffalo Trace)", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I had a bourbon at dinner tonight.",
                assistant_response="A treat?",
                rationale="One serving doesn't define a preference.",
                negative_extractions=(
                    MemoryFact(text="User prefers bourbon", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="car_subaru",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I've owned three Subarus in a row — I can't drive anything else now.",
                assistant_response="What is it about them?",
                rationale="Three of the same brand + exclusionary statement.",
                memories_to_extract=(
                    MemoryFact(text="User strongly prefers Subaru cars", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="The rental I had last week was a Subaru.",
                assistant_response="How was it?",
                rationale="Rental, not a chosen vehicle.",
                negative_extractions=(
                    MemoryFact(text="User likes Subaru", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="clothing_patagonia",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I buy almost all my outdoor gear from Patagonia.",
                assistant_response="They've got durable stuff.",
                rationale="Habitual brand purchase pattern.",
                memories_to_extract=(
                    MemoryFact(text="User prefers Patagonia for outdoor gear", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I picked up a Patagonia jacket on sale yesterday.",
                assistant_response="Nice find.",
                rationale="Single purchase doesn't establish brand loyalty.",
                negative_extractions=(
                    MemoryFact(text="User prefers Patagonia", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sport_running",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I run every morning — five miles before work.",
                assistant_response="Impressive consistency.",
                rationale="Daily routine — durable habit.",
                memories_to_extract=(
                    MemoryFact(text="User runs five miles every morning before work", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I went for a jog this morning, first one in months.",
                assistant_response="How did it feel?",
                rationale="Explicitly first-in-months, not a habit.",
                negative_extractions=(
                    MemoryFact(text="User runs regularly", category="interest", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="game_chess",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="Chess is my main hobby — I play in a club every weekend.",
                assistant_response="What's your rating?",
                rationale="Hobby + recurring club activity.",
                memories_to_extract=(
                    MemoryFact(text="User plays chess regularly at a club on weekends", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I played a game of chess on the train today.",
                assistant_response="Win?",
                rationale="One-off play, no hobby signal.",
                negative_extractions=(
                    MemoryFact(text="User plays chess regularly", category="interest", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="exercise_yoga",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="I do yoga every Tuesday and Thursday at the studio down the street.",
                assistant_response="Vinyasa or hatha?",
                rationale="Twice-weekly routine — durable practice.",
                memories_to_extract=(
                    MemoryFact(text="User attends yoga classes Tuesdays and Thursdays at a local studio", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I tried a yoga class for the first time yesterday.",
                assistant_response="What did you think?",
                rationale="Trial class, not a routine.",
                negative_extractions=(
                    MemoryFact(text="User does yoga regularly", category="interest", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="shoe_nike",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="My closet is basically all Nikes — I won't run in anything else.",
                assistant_response="Pegasus or Vaporfly?",
                rationale="Heavy brand commitment + exclusion.",
                memories_to_extract=(
                    MemoryFact(text="User strongly prefers Nike running shoes", category="preference", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="The Nike pair I bought last week is starting to wear out already.",
                assistant_response="That's frustrating.",
                rationale="Single purchase, mostly negative experience.",
                negative_extractions=(
                    MemoryFact(text="User prefers Nike", category="preference", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="podcast_ezraklein",
        pair_type="implicit_vs_explicit_preference",
        sides=(
            MemoryProto(
                user_message="The Ezra Klein Show is my podcast — I listen to every episode.",
                assistant_response="Any favorite recent ones?",
                rationale="Every-episode commitment marks a strong preference.",
                memories_to_extract=(
                    MemoryFact(text="User listens to The Ezra Klein Show regularly", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I caught one Ezra Klein episode on the bus this week.",
                assistant_response="What did you think?",
                rationale="Single listen, no commitment.",
                negative_extractions=(
                    MemoryFact(text="User listens to The Ezra Klein Show", category="interest", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
)


# =============================================================================
# Type 2: temporary_vs_stable (16 pairs)
# =============================================================================
# side[0]: STABLE durable fact — extract as stable.
# side[1]: TEMPORARY situation — extract as temporary_context with expiration.
# Both sides extract; the test is the labeler distinguishing duration.
# =============================================================================
#
# expiration_iso_date placeholders use templating tokens — replaced at
# template render time. We use static ISO dates here for the expiration
# (rendered relative to today via the expansion prompt). The driver injects
# today_iso / today_plus_7_iso / today_plus_30_iso for the expansion prompt
# to use.

TEMPORARY_VS_STABLE: tuple[MemoryProtoPair, ...] = (
    MemoryProtoPair(
        pair_id="job_engineer_vs_side_project",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I'm a senior software engineer at Stripe — been there four years.",
                assistant_response="That's a great team.",
                rationale="Stable employment fact.",
                memories_to_extract=(
                    MemoryFact(text="User is a senior software engineer at Stripe (4+ years tenure)", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm working on a side project this weekend — building a recipe app.",
                assistant_response="Sounds fun, what's the stack?",
                rationale="Weekend project — temporary, ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is working on a side project building a recipe app this weekend", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="city_toronto_vs_tokyo_trip",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I've lived in Toronto for over a decade.",
                assistant_response="A great city.",
                rationale="Long-term residence.",
                memories_to_extract=(
                    MemoryFact(text="User lives in Toronto (10+ years)", category="personal_identity", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm heading to Tokyo next week for a conference.",
                assistant_response="Have you been before?",
                rationale="Trip with a clear end date — temporary.",
                memories_to_extract=(
                    MemoryFact(text="User is traveling to Tokyo next week for a conference", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="married_vs_date",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="My wife and I have been married for five years.",
                assistant_response="Congrats on five.",
                rationale="Long-term relationship fact.",
                memories_to_extract=(
                    MemoryFact(text="User has been married for 5 years", category="relationship", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I have a date this Friday with someone I met online.",
                assistant_response="Hope it goes well.",
                rationale="Single upcoming date — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User has a date scheduled this Friday with someone they met online", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="phd_vs_hackathon",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I'm in my third year of a PhD in computational biology.",
                assistant_response="What's your dissertation focus?",
                rationale="Multi-year academic program — stable.",
                memories_to_extract=(
                    MemoryFact(text="User is a third-year PhD student in computational biology", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm doing a 48-hour hackathon this weekend.",
                assistant_response="Theme?",
                rationale="48-hour event — clearly ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is participating in a 48-hour hackathon this weekend", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="learning_spanish_vs_class",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I've been learning Spanish for two years — almost B2 now.",
                assistant_response="Big milestone coming up.",
                rationale="Multi-year ongoing skill — stable interest.",
                memories_to_extract=(
                    MemoryFact(text="User has been learning Spanish for 2 years and is approaching B2 level", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm taking a one-day Spanish workshop on Saturday.",
                assistant_response="What level?",
                rationale="Single workshop — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is taking a one-day Spanish workshop on Saturday", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="boston_vs_nyc_visit",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I'm a Boston resident — born and raised.",
                assistant_response="Where in Boston?",
                rationale="Long-term residence.",
                memories_to_extract=(
                    MemoryFact(text="User is a lifelong Boston resident", category="personal_identity", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm visiting NYC this week to see family.",
                assistant_response="Hope the weather cooperates.",
                rationale="Short visit — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is visiting NYC this week to see family", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="remote_perm_vs_wfh_today",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I work fully remote — my company is distributed.",
                assistant_response="What's your home setup like?",
                rationale="Permanent work arrangement.",
                memories_to_extract=(
                    MemoryFact(text="User works fully remote at a distributed company", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm working from home today because the office is closed for maintenance.",
                assistant_response="Quieter day at least.",
                rationale="One-day situation — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is working from home today due to office maintenance", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="soccer_team_vs_pickup",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I play on an adult soccer league every Sunday — been on the same team for years.",
                assistant_response="What position?",
                rationale="Recurring activity — durable.",
                memories_to_extract=(
                    MemoryFact(text="User plays on an adult soccer league team every Sunday", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="Joining a pickup soccer game tomorrow with some coworkers.",
                assistant_response="Have fun.",
                rationale="One pickup session — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is playing in a pickup soccer game with coworkers tomorrow", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="senior_eng_vs_interim_manager",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I've been a senior engineer at my company for six years.",
                assistant_response="Long tenure.",
                rationale="Long-tenured role.",
                memories_to_extract=(
                    MemoryFact(text="User has been a senior engineer at their company for 6 years", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm filling in as interim manager for the next month while my boss is on leave.",
                assistant_response="That's a stretch assignment.",
                rationale="Time-bound stretch role — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is acting as interim manager for one month covering boss's leave", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="nyu_undergrad_vs_audit_class",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I'm an NYU undergraduate, junior year, double-majoring in CS and philosophy.",
                assistant_response="Tough double.",
                rationale="Multi-year academic identity.",
                memories_to_extract=(
                    MemoryFact(text="User is an NYU undergraduate junior majoring in CS and philosophy", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm auditing a single class on AI ethics this semester for fun.",
                assistant_response="What's the prof?",
                rationale="One-semester audit — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is auditing an AI ethics class this semester", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="parent_vs_visit_nephew",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I have two kids, ages 4 and 7.",
                assistant_response="Busy household.",
                rationale="Durable family fact.",
                memories_to_extract=(
                    MemoryFact(text="User has two children, ages 4 and 7", category="relationship", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="My nephew is staying with us this week while my sister is on a work trip.",
                assistant_response="Nice for him.",
                rationale="One-week visit — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User's nephew is staying with them this week", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="founder_vs_consulting_gig",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I founded my company three years ago — we're a small B2B SaaS.",
                assistant_response="What do you sell?",
                rationale="Long-tenured founder role.",
                memories_to_extract=(
                    MemoryFact(text="User founded a small B2B SaaS company 3 years ago", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm doing a one-month consulting gig with another startup on the side.",
                assistant_response="Anything interesting?",
                rationale="One-month gig — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is doing a one-month consulting gig with a startup", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="guitar_vs_jam_session",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I've been playing guitar daily for 15 years.",
                assistant_response="Any favorite styles?",
                rationale="Lifelong skill / hobby.",
                memories_to_extract=(
                    MemoryFact(text="User has played guitar daily for 15 years", category="interest", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm joining a jam session at a friend's place tonight.",
                assistant_response="Fun.",
                rationale="One evening session — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is joining a jam session at a friend's place tonight", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_7__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="own_tesla_vs_rental",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I drive a Tesla Model 3 — bought it new in 2023.",
                assistant_response="How are you finding it?",
                rationale="Durable vehicle ownership.",
                memories_to_extract=(
                    MemoryFact(text="User owns a Tesla Model 3 (bought new in 2023)", category="personal_identity", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I have a rental Hyundai for the next two weeks while my car is in the shop.",
                assistant_response="Hope they fix it fast.",
                rationale="Two-week rental — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is driving a rental Hyundai for two weeks while their car is in the shop", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="finance_career_vs_rotation",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="I've been in finance for ten years — currently at a hedge fund.",
                assistant_response="Quant or fundamentals?",
                rationale="Decade-long career path.",
                memories_to_extract=(
                    MemoryFact(text="User has been in finance for 10 years and currently works at a hedge fund", category="professional", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm on a six-week rotation in our risk team to broaden experience.",
                assistant_response="Different mindset?",
                rationale="Six-week rotation — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is on a six-week rotation in their company's risk team", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="paris_home_vs_abroad_month",
        pair_type="temporary_vs_stable",
        sides=(
            MemoryProto(
                user_message="My family lives in Paris — we've been here since 2015.",
                assistant_response="Which arrondissement?",
                rationale="Long-term residence.",
                memories_to_extract=(
                    MemoryFact(text="User's family has lived in Paris since 2015", category="personal_identity", stability="stable"),
                ),
            ),
            MemoryProto(
                user_message="I'm spending the next month working from Lisbon for a change of scenery.",
                assistant_response="Lisbon's beautiful.",
                rationale="One-month working stay — ephemeral.",
                memories_to_extract=(
                    MemoryFact(text="User is working from Lisbon for one month", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
)


# =============================================================================
# Type 3: sensitive (16 individual examples)
# =============================================================================
# Sensitive disclosures where the default rule is err on the side of NOT
# extracting unless the user clearly volunteers it as durable context.
# These are NOT pairs — each is a distinct scenario.

SENSITIVE_EXAMPLES: tuple[MemoryProtoPair, ...] = (
    MemoryProtoPair(
        pair_id="sensitive_diabetes_diagnosis",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="I was diagnosed with Type 2 diabetes last year — I track my blood sugar daily now.",
                assistant_response="Living with it long-term changes routines a lot.",
                rationale="Durable medical condition explicitly volunteered.",
                memories_to_extract=(
                    MemoryFact(text="User has Type 2 diabetes and tracks blood sugar daily", category="personal_identity", stability="stable"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_one_bad_night",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="I'm just feeling really down today, didn't sleep well.",
                assistant_response="Sorry to hear that. Anything specific weighing on you?",
                rationale="One bad night is not durable evidence of insomnia or chronic mood issues.",
                negative_extractions=(
                    MemoryFact(text="User has chronic insomnia", category="personal_identity", stability="stable", confidence="low"),
                    MemoryFact(text="User has depression", category="personal_identity", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_mortgage_stress",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="The mortgage payment hit hard this month — barely covered it.",
                assistant_response="That's a tough spot.",
                rationale="Transient financial venting; not a durable financial-status disclosure.",
                negative_extractions=(
                    MemoryFact(text="User is in financial distress", category="personal_identity", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_divorce",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="My divorce was finalized last month after a long process.",
                assistant_response="Big life change.",
                rationale="Major durable life event explicitly stated.",
                memories_to_extract=(
                    MemoryFact(text="User finalized their divorce last month", category="relationship", stability="stable"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_argument",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="My partner and I had a big fight tonight, I'm not sure how to handle it.",
                assistant_response="Some space might help, then a calm conversation.",
                rationale="One argument is not a durable relationship-status fact.",
                negative_extractions=(
                    MemoryFact(text="User's relationship with partner is troubled", category="relationship", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_pregnancy",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="My wife is pregnant — we're due in November.",
                assistant_response="Big congrats.",
                rationale="Time-bound but durable family fact.",
                memories_to_extract=(
                    MemoryFact(text="User's wife is pregnant, due in November", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_depression_diagnosis",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="I've been managing depression for a few years now — meds and therapy help.",
                assistant_response="Glad you've found a routine that works.",
                rationale="Durable mental health context explicitly disclosed.",
                memories_to_extract=(
                    MemoryFact(text="User has depression managed with medication and therapy", category="personal_identity", stability="stable"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_one_low_day",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="Just been feeling kind of off today, hard to focus.",
                assistant_response="That happens. Anything specific going on?",
                rationale="One off day — does not warrant a durable mental-health extraction.",
                negative_extractions=(
                    MemoryFact(text="User has attention difficulties", category="personal_identity", stability="stable", confidence="low"),
                    MemoryFact(text="User struggles with mood", category="personal_identity", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_lost_job",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="I got laid off last week — looking for the next thing now.",
                assistant_response="Sorry to hear that. What field?",
                rationale="Significant durable employment-status change.",
                memories_to_extract=(
                    MemoryFact(text="User was laid off last week and is job searching", category="professional", stability="evolving"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_small_bill",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="Got a $200 bill I wasn't expecting, ugh.",
                assistant_response="Annoying but workable.",
                rationale="One unexpected bill — not durable financial state.",
                negative_extractions=(
                    MemoryFact(text="User struggles with money", category="personal_identity", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_chronic_back",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="I've had chronic lower back pain for years — physical therapy helps but it never fully goes away.",
                assistant_response="That's a lot to live with.",
                rationale="Long-term medical condition disclosed.",
                memories_to_extract=(
                    MemoryFact(text="User has chronic lower back pain managed with physical therapy", category="personal_identity", stability="stable"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_minor_cold",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="Caught a cold this weekend, slow to shake it.",
                assistant_response="Hope you feel better soon.",
                rationale="Transient illness, not a durable health fact.",
                negative_extractions=(
                    MemoryFact(text="User has weak immune system", category="personal_identity", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_parenting_struggle",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="My oldest has ADHD and we've been navigating it for the last two years — meds, therapy, school accommodations.",
                assistant_response="A lot to manage but you're clearly on top of it.",
                rationale="Durable family-context disclosure.",
                memories_to_extract=(
                    MemoryFact(text="User's oldest child has ADHD and is on meds with therapy + school accommodations", category="relationship", stability="stable"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_kid_sick_today",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="My kid is home sick from school today.",
                assistant_response="Hope it passes quickly.",
                rationale="One sick day is not a durable health or family fact.",
                negative_extractions=(
                    MemoryFact(text="User's child has a chronic illness", category="relationship", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_inheritance",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="I inherited a property from my late grandfather — figuring out whether to sell or rent it out.",
                assistant_response="Big decision.",
                rationale="Durable financial event affecting future planning.",
                memories_to_extract=(
                    MemoryFact(text="User inherited a property from their late grandfather and is deciding to sell or rent", category="temporary_context", stability="ephemeral", expiration_iso_date="__TODAY_PLUS_30__"),
                ),
            ),
        ),
    ),
    MemoryProtoPair(
        pair_id="sensitive_overspending_week",
        pair_type="sensitive",
        sides=(
            MemoryProto(
                user_message="Spent way too much this week, need to dial it back.",
                assistant_response="Resetting next week is doable.",
                rationale="Single week of overspending — not a durable financial trait.",
                negative_extractions=(
                    MemoryFact(text="User has poor financial discipline", category="personal_identity", stability="stable", confidence="low"),
                ),
            ),
        ),
    ),
)


# =============================================================================
# Aggregate
# =============================================================================

ALL_MEMORY_PROTOTYPES: tuple[MemoryProtoPair, ...] = (
    *IMPLICIT_VS_EXPLICIT,
    *TEMPORARY_VS_STABLE,
    *SENSITIVE_EXAMPLES,
)
