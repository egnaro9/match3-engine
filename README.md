# match3-engine

[![ci](https://github.com/egnaro9/match3-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/egnaro9/match3-engine/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![tests](https://img.shields.io/badge/tests-59%20(43%20example%20%2B%2016%20property)-brightgreen)](src/test/java/io/github/egnaro9/match3)
[![live demo](https://img.shields.io/badge/demo-play%20it%20in%20your%20browser-f2a53c)](https://egnaro9.github.io/match3-engine/)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**The match-3 rules engine from a shipping Android game — extracted, dependency-free, and pinned down by 16 property-based invariants.**

This is the real logic layer of [Hero Gem](https://egnaro9.github.io), a React/Capacitor game where a pure-Java engine on-device is the authority and the web layer is just the renderer. It's lifted out here as a standalone library so you can read it and run its tests without an Android SDK, an emulator, or a device:

```bash
git clone https://github.com/egnaro9/match3-engine && cd match3-engine
./gradlew test        # 59 tests, ~2 seconds, zero setup
```

### ▶ [Play it in your browser](https://egnaro9.github.io/match3-engine/)

The engine compiles to JavaScript with [TeaVM](https://teavm.org) (~50 KB gzipped), so you can **play a real board driven by this Java** — it decides every match, every special, every refill. The compiler config lives in a separate `demo-js` module, so the library keeps its zero-dependency guarantee.

Zero dependencies outside the JDK. ~570 lines of engine, **1,200+ lines of tests** — the ratio is the point.

> **What porting it found.** Gem ids were minted from `System.nanoTime()` + a random number. On a JVM that's fine — 128,000 gems, zero collisions. Browsers clamp their clock (~100µs, a Spectre mitigation), so `nanoTime` barely advances and the same code produced **301 duplicate ids per 128,000 gems**. Those ids are renderer identity keys, so collisions mean gems animating as one. It's now a monotonic counter — uniqueness that doesn't depend on the platform's clock. Running the same code somewhere new is what exposed it; the JVM alone never could.

---

## Why property tests, not just examples

Example tests check the boards you thought of. Match-3 breaks on the boards you didn't: the L that's also a T, the run clipped by the wall, the color bomb that forgets to consume itself, the blocker sitting mid-run.

So the suite has two halves. 35 **example tests** nail down specific known shapes (3/4/5-in-row, L, T, cross, blocker-broken runs, edge-clipped activations). 16 **[jqwik](https://jqwik.net/) property tests** then assert *invariants* — statements that must hold for **every** board — against generated boards, and jqwik shrinks any counterexample to a minimal failing case.

The generator is deliberately **biased**: a uniformly random 8×8 board almost never contains an interesting match, so it plants horizontal/vertical runs and L/T shapes on purpose. An unbiased generator would pass every property while testing nothing.

The suite carries a hard rule, written into its header:

> **A jqwik counterexample = a real engine bug. Do NOT edit the engine to make a property pass — fix the engine instead.**

## The invariants

**Detection (A1–A7)**

| | Invariant |
|---|---|
| A1 | Every `findMatches` key is inside a ≥3 contiguous same-type non-blocker run |
| A2 | `intersection == true` **iff** the cell is covered by both an H and a V run |
| A3 | A BLOCKER is never in a match and always breaks a run |
| A4 | `checkMatchAt(b,r,c)` ⟺ `"r-c" ∈ findMatches(b)` |
| A5 | `wouldCreateMatch(b,swap)` ⟺ `findMatches(swapGems(b,swap))` is non-empty |
| A6 | `hasAnyValidMove(b)` ⟺ ∃ an adjacent non-blocker swap with `wouldCreateMatch` true |
| A7 | `findMatches` does not mutate its input |

**Activation (B1–B7)**

| | Invariant |
|---|---|
| B1 | STRIPED_H marks exactly its row's non-blocker cells — nothing off-row |
| B2 | STRIPED_V marks exactly its column's non-blocker cells — nothing off-column |
| B3 | WRAPPED marks exactly the (edge-clipped) 3×3 around its position |
| B4 | COLOR_BOMB marks exactly every non-blocker cell of the target type **and itself** |
| B5 | Blocker immunity: no activation ever marks a blocker |
| B6 | Activating a non-special cell is a no-op |
| B7 | Activation never mutates its input |

**Effects (C1–C2)**

| | Invariant |
|---|---|
| C1 | `combinedFxType` is never null across all 16 SpecialType pairs |
| C2 | `combinedFxType` is symmetric |

A4, A5 and A6 are the interesting ones: each pins a *fast* predicate to the *slow, obviously-correct* one. `checkMatchAt` scans outward from a point; `findMatches` sweeps the whole board. They must agree everywhere — and a property test is what proves it, since the fast path is exactly where a subtle scanning bug hides.

## What it implements

- **Dual-axis run detection** with a sentinel loop that runs one past the edge, so a run ending at the wall closes like any other. (The `col == BOARD_SIZE` bound is checked *first* — deliberately — to short-circuit before the read that would go out of bounds.)
- **Intersection detection** for L / T / cross shapes: a cell claimed by both scans is flagged, not overwritten — that flag is what earns a wrapped gem.
- **Blockers** that break runs and are immune to every clear.
- **Four special types** with distinct geometry, plus the full **combination matrix**:

  | | bomb | wrapped | striped |
  |---|---|---|---|
  | **bomb** | clear the board | clear partner's color | clear partner's color |
  | **wrapped** | — | 5×5 blast | 3 rows **and** 3 columns |
  | **striped** | — | — | one row + one column (cross) |

- **Gravity and refill** — columns rebuilt bottom-up from survivors, preserving order.
- **Board generation** with spawn-match resolution and a solvability retry loop, so a level never opens deadlocked.

## API

```java
LevelConfig cfg = LevelConfig.plain();               // or new LevelConfig(ice, chain, blocker, difficulty)
GameBoard.Gem[][] board = BoardEngine.createBoard(cfg);

BoardEngine.hasAnyValidMove(board);                  // deadlock check
BoardEngine.wouldCreateMatch(board, r1, c1, r2, c2); // speculative, non-mutating
HashMap<String, MatchInfo> m = BoardEngine.findMatches(board);   // keyed "row-col"
board = BoardEngine.activateSpecialGem(board, r, c, partnerType);
board = BoardEngine.combinedActivate(board, r1, c1, r2, c2);
board = BoardEngine.applyGravityAndRefill(board, true);
```

Everything is `static`; `Gem` is immutable. That's what makes `deepCopy` a per-row `System.arraycopy` and speculative moves cheap — nothing can mutate out from under a copy.

## Design notes

- **Why immutable gems?** The engine answers "would this swap match?" constantly (`hasAnyValidMove` alone runs it ~112× per call). With immutable cells, a board copy is a shallow row copy and speculation has no side effects to unwind.
- **Why two match predicates?** `checkMatchAt` scans both directions; `hasMatchAt`/`wouldMatch` only look backward. Generation fills top-left → bottom-right, so the cells *ahead* don't exist yet — looking forward there would read unfilled state. Subtle, and exactly the kind of thing A4 exists to police.
- **What's not here.** The cascade loop, special-gem creation rules, and scoring live in the app's Capacitor plugin, welded to `SoundPool`/`Vibrator`/`Handler`. This library is the stateless primitives those rules are built from. Lifting the cascade out behind a listener interface is the natural next step — it would make the app's rules testable the same way these are.

## Porting notes (if you compile this to JS yourself)

- **Seeded `Random.nextInt(bound)` diverges between TeaVM and the JVM.** TeaVM's `Random` doesn't override the bounded variant, so it inherits Java 17's `RandomGenerator` default (mask-and-reject) while the JVM uses `Random`'s legacy `% bound`. `nextInt()`, `nextLong()`, `nextDouble()` and `nextBoolean()` are bit-exact. Irrelevant when the RNG is unseeded (as it is here), but it would silently break **seed-locked JVM-vs-JS differential testing** — if you ever want that, inject the RNG and derive bounded draws from `nextInt()` yourself rather than trusting `nextInt(bound)` to match.
- **Don't rely on the clock for identity.** See the gem-id note above: this is the general form of that lesson.

## Extraction notes

This is a **one-time extraction, not a live mirror** — it has deliberately diverged from the game (a `LevelConfig` value type, no Capacitor serializer, the monotonic id counter, `markMatched`). Don't assume the two are in sync.

Faithfully lifted from the shipping game, with three changes: the Capacitor `JSArray` serializer was dropped (serialization belongs to the plugin layer, not the rules), `createBoard` takes a `LevelConfig` value type instead of a `JSONObject` (which is what removes the last non-JDK dependency), and the package was renamed. The tests are unmodified apart from that rename — they're the same 51 that guard the engine in production.

---

Built by [Erik Hill](https://egnaro9.github.io) · MIT licensed.
