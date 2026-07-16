// Stateless match-3 rules engine. Every method is static; Gem is immutable, so
// mutation returns new instances and speculative moves cost nothing but a copy.
// Board representation: Gem[BOARD_SIZE][BOARD_SIZE], indexed [row][col].
package io.github.egnaro9.match3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public final class BoardEngine {

    private static final GameBoard.GemType[] BASE_TYPES = {
        GameBoard.GemType.RED, GameBoard.GemType.BLUE, GameBoard.GemType.GREEN,
        GameBoard.GemType.YELLOW, GameBoard.GemType.PURPLE
    };

    private static final Random RNG = new Random();

    /**
     * Monotonic source of unique gem ids.
     *
     * <p>These ids are identity keys for the renderer, so collisions are not
     * cosmetic — two gems sharing an id animate as one. The obvious
     * {@code nanoTime() + random} scheme is only <em>probabilistically</em>
     * unique, and how probable depends on the clock underneath: a JVM's
     * high-resolution timer hides the flaw, while a browser clamps its clock
     * (~100µs, a Spectre mitigation) and the same code collides hundreds of
     * times per 100k gems. A counter is unconditionally unique on every clock,
     * so the guarantee stops depending on the platform.
     */
    private static long idSeq = 0L;

    private static synchronized long nextId() {
        return idSeq++;
    }

    // ── Copy helpers ──────────────────────────────────────────────────────────

    // Gem objects are immutable, so a per-row System.arraycopy is a full deep copy.
    static GameBoard.Gem[][] deepCopy(GameBoard.Gem[][] board) {
        GameBoard.Gem[][] copy = new GameBoard.Gem[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            System.arraycopy(board[r], 0, copy[r], 0, GameBoard.BOARD_SIZE);
        }
        return copy;
    }

    // Returns a new Gem with a different type; all other fields unchanged.
    private static GameBoard.Gem withType(GameBoard.Gem g, GameBoard.GemType type) {
        return new GameBoard.Gem(type, g.id, g.matched, g.falling, g.special, g.ice, g.chain, g.blocker);
    }

    // Returns a new Gem with matched=true; all other fields unchanged.
    private static GameBoard.Gem withMatched(GameBoard.Gem g) {
        return new GameBoard.Gem(g.type, g.id, true, g.falling, g.special, g.ice, g.chain, g.blocker);
    }

    // ── Deadlock detection ────────────────────────────────────────────────────

    /**
     * Is the board still playable? True if any adjacent swap would create a match.
     * Only right/down neighbours are tested — every pair is reachable that way, so
     * checking left/up as well would just re-test the same swaps.
     */
    public static boolean hasAnyValidMove(GameBoard.Gem[][] board) {
        for (int row = 0; row < GameBoard.BOARD_SIZE; row++) {
            for (int col = 0; col < GameBoard.BOARD_SIZE; col++) {
                if (board[row][col].blocker) continue;
                if (col + 1 < GameBoard.BOARD_SIZE
                        && !board[row][col + 1].blocker
                        && wouldCreateMatch(board, row, col, row, col + 1)) return true;
                if (row + 1 < GameBoard.BOARD_SIZE
                        && !board[row + 1][col].blocker
                        && wouldCreateMatch(board, row, col, row + 1, col)) return true;
            }
        }
        return false;
    }

    /** Would swapping these two positions produce a match at either end? Non-mutating. */
    public static boolean wouldCreateMatch(GameBoard.Gem[][] board, int r1, int c1, int r2, int c2) {
        GameBoard.Gem[][] temp = deepCopy(board);
        GameBoard.Gem t = temp[r1][c1];
        temp[r1][c1] = temp[r2][c2];
        temp[r2][c2] = t;
        return checkMatchAt(temp, r1, c1) || checkMatchAt(temp, r2, c2);
    }

    /**
     * Is this position part of a run of 3+? Scans outward in both directions,
     * so it detects a run the position sits anywhere inside.
     * Blockers never match.
     */
    public static boolean checkMatchAt(GameBoard.Gem[][] board, int row, int col) {
        GameBoard.GemType type = board[row][col].type;
        if (type == GameBoard.GemType.BLOCKER) return false;

        int hCount = 1;
        for (int c = col - 1; c >= 0 && board[row][c].type == type; c--) hCount++;
        for (int c = col + 1; c < GameBoard.BOARD_SIZE && board[row][c].type == type; c++) hCount++;
        if (hCount >= 3) return true;

        int vCount = 1;
        for (int r = row - 1; r >= 0 && board[r][col].type == type; r--) vCount++;
        for (int r = row + 1; r < GameBoard.BOARD_SIZE && board[r][col].type == type; r++) vCount++;
        return vCount >= 3;
    }

    /**
     * Backward-only lookback: would placing {@code type} here complete a run with the
     * two cells already to the left / above? Used during generation, where cells are
     * filled top-left to bottom-right and the cells ahead don't exist yet.
     */
    public static boolean wouldMatch(GameBoard.Gem[][] board, int row, int col, GameBoard.GemType type) {
        if (col >= 2 && board[row][col - 1].type == type && board[row][col - 2].type == type) return true;
        if (row >= 2 && board[row - 1][col].type == type && board[row - 2][col].type == type) return true;
        return false;
    }

    /** Backward-only variant of {@link #checkMatchAt}, for the same generation-order reason. */
    public static boolean hasMatchAt(GameBoard.Gem[][] board, int row, int col) {
        GameBoard.GemType type = board[row][col].type;
        if (type == GameBoard.GemType.BLOCKER) return false;
        if (col >= 2 && board[row][col - 1].type == type && board[row][col - 2].type == type) return true;
        if (row >= 2 && board[row - 1][col].type == type && board[row - 2][col].type == type) return true;
        return false;
    }

    /**
     * Resolve matches present at spawn time by recoloring offenders.
     * Prefers a color that creates no new match; if every color would match
     * (possible in tight corners), falls back to any color and re-loops.
     * Mutates in place.
     */
    public static GameBoard.Gem[][] removeInitialMatches(GameBoard.Gem[][] board) {
        for (int row = 0; row < GameBoard.BOARD_SIZE; row++) {
            for (int col = 0; col < GameBoard.BOARD_SIZE; col++) {
                if (board[row][col].blocker) continue;
                while (hasMatchAt(board, row, col)) {
                    List<GameBoard.GemType> available = new ArrayList<>();
                    for (GameBoard.GemType t : BASE_TYPES) {
                        if (!wouldMatch(board, row, col, t)) available.add(t);
                    }
                    GameBoard.GemType newType = available.isEmpty()
                            ? BASE_TYPES[RNG.nextInt(BASE_TYPES.length)]
                            : available.get(RNG.nextInt(available.size()));
                    board[row][col] = withType(board[row][col], newType);
                }
            }
        }
        return board;
    }

    // ── Special-gem activation ────────────────────────────────────────────────

    public static GameBoard.Gem[][] activateSpecialGem(GameBoard.Gem[][] board, int row, int col) {
        return activateSpecialGem(board, row, col, null);
    }

    /**
     * Fire one special gem, returning a new board with everything it clears flagged
     * {@code matched}. Blockers are immune to every effect.
     *
     * @param partnerType for COLOR_BOMB, the swap partner's color — the bomb clears
     *                    that color rather than its own. Null falls back to its own.
     */
    public static GameBoard.Gem[][] activateSpecialGem(
            GameBoard.Gem[][] board, int row, int col, GameBoard.GemType partnerType) {
        GameBoard.Gem gem = board[row][col];
        if (gem.special == null) return board;

        GameBoard.Gem[][] nb = deepCopy(board);
        switch (gem.special) {
            case STRIPED_H:
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                    if (!nb[row][c].blocker) nb[row][c] = withMatched(nb[row][c]);
                break;
            case STRIPED_V:
                for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
                    if (!nb[r][col].blocker) nb[r][col] = withMatched(nb[r][col]);
                break;
            case WRAPPED:
                for (int r = Math.max(0, row - 1); r <= Math.min(GameBoard.BOARD_SIZE - 1, row + 1); r++)
                    for (int c = Math.max(0, col - 1); c <= Math.min(GameBoard.BOARD_SIZE - 1, col + 1); c++)
                        if (!nb[r][c].blocker) nb[r][c] = withMatched(nb[r][c]);
                break;
            case COLOR_BOMB:
                GameBoard.GemType target = (partnerType != null) ? partnerType : gem.type;
                for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
                    for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                        if (nb[r][c].type == target && !nb[r][c].blocker)
                            nb[r][c] = withMatched(nb[r][c]);
                // The bomb is a different color than its target, so the sweep above
                // misses it. Mark it explicitly or it survives its own detonation.
                nb[row][col] = withMatched(nb[row][col]);
                break;
        }
        return nb;
    }

    // ── Match detection ───────────────────────────────────────────────────────

    /**
     * One entry per matched position.
     *
     * <p>{@code direction} is "h" or "v". For "h", {@code row}/{@code col} are the row
     * and start column of the run; for "v", the start row and column.
     * {@code intersection} means the position is covered by a horizontal AND a vertical
     * run — i.e. the elbow of an L or T — which is what a caller keys "this match earns
     * a wrapped gem" off of.
     */
    public static class MatchInfo {
        public final int length;
        public final String direction;
        public final int row;
        public final int col;
        public boolean intersection;

        public MatchInfo(int length, String direction, int row, int col) {
            this.length = length;
            this.direction = direction;
            this.row = row;
            this.col = col;
            this.intersection = false;
        }
    }

    /**
     * Generate a playable board.
     *
     * <p>Obstacles are rolled per cell, spawn matches are resolved, and the whole
     * board is re-rolled until it has at least one legal move (bounded at 10
     * attempts so a pathological config can't spin forever).
     */
    public static GameBoard.Gem[][] createBoard(LevelConfig config) {
        double doubleChainChance = config.isHardPlus() ? 0.5 : 0.25;

        GameBoard.Gem[][] board;
        int attempts = 0;

        do {
            board = new GameBoard.Gem[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
            for (int row = 0; row < GameBoard.BOARD_SIZE; row++) {
                for (int col = 0; col < GameBoard.BOARD_SIZE; col++) {
                    String id = row + "-" + col + "-" + nextId();
                    boolean isBlocker = config.blockerProbability > 0
                            && RNG.nextDouble() < config.blockerProbability;
                    if (isBlocker) {
                        board[row][col] = new GameBoard.Gem(
                            GameBoard.GemType.BLOCKER, id, false, false, null, false, 0, true);
                    } else {
                        boolean hasIce = config.iceProbability > 0
                                && RNG.nextDouble() < config.iceProbability;
                        int chainLevel = 0;
                        if (config.chainProbability > 0 && RNG.nextDouble() < config.chainProbability) {
                            chainLevel = RNG.nextDouble() < doubleChainChance ? 2 : 1;
                        }
                        board[row][col] = new GameBoard.Gem(
                            BASE_TYPES[RNG.nextInt(BASE_TYPES.length)], id,
                            false, false, null, hasIce, chainLevel, false);
                    }
                }
            }
            board = removeInitialMatches(board);
            attempts++;
        } while (!hasAnyValidMove(board) && attempts < 10);

        return board;
    }

    /**
     * Find every match on the board, keyed "row-col".
     *
     * <p>Scans each axis with a sentinel loop that runs one past the edge, so a run
     * ending at the wall closes the same way as one ending at a color change. The
     * {@code col == BOARD_SIZE} test is deliberately ordered first — it short-circuits
     * before the {@code board[row][col]} read that would otherwise go out of bounds.
     *
     * <p>A position written by both scans is an intersection (the elbow of an L/T),
     * flagged rather than overwritten.
     */
    public static HashMap<String, MatchInfo> findMatches(GameBoard.Gem[][] board) {
        HashMap<String, MatchInfo> matches = new HashMap<>();

        // Horizontal scan
        for (int row = 0; row < GameBoard.BOARD_SIZE; row++) {
            int matchStart = 0;
            for (int col = 1; col <= GameBoard.BOARD_SIZE; col++) {
                GameBoard.GemType prevType = board[row][col - 1].type;
                boolean boundary = (col == GameBoard.BOARD_SIZE)
                                || (board[row][col].type != prevType)
                                || (prevType == GameBoard.GemType.BLOCKER);
                if (boundary) {
                    int matchLength = col - matchStart;
                    if (matchLength >= 3 && prevType != GameBoard.GemType.BLOCKER) {
                        for (int i = matchStart; i < col; i++) {
                            String key = row + "-" + i;
                            MatchInfo existing = matches.get(key);
                            if (existing == null) {
                                matches.put(key, new MatchInfo(matchLength, "h", row, matchStart));
                            } else {
                                existing.intersection = true;
                            }
                        }
                    }
                    matchStart = col;
                }
            }
        }

        // Vertical scan
        for (int col = 0; col < GameBoard.BOARD_SIZE; col++) {
            int matchStart = 0;
            for (int row = 1; row <= GameBoard.BOARD_SIZE; row++) {
                GameBoard.GemType prevType = board[row - 1][col].type;
                boolean boundary = (row == GameBoard.BOARD_SIZE)
                                || (board[row][col].type != prevType)
                                || (prevType == GameBoard.GemType.BLOCKER);
                if (boundary) {
                    int matchLength = row - matchStart;
                    if (matchLength >= 3 && prevType != GameBoard.GemType.BLOCKER) {
                        for (int i = matchStart; i < row; i++) {
                            String key = i + "-" + col;
                            MatchInfo existing = matches.get(key);
                            if (existing == null) {
                                matches.put(key, new MatchInfo(matchLength, "v", matchStart, col));
                            } else {
                                existing.intersection = true;
                            }
                        }
                    }
                    matchStart = row;
                }
            }
        }

        return matches;
    }

    /**
     * Flag every position in {@code keys} as matched, returning a new board.
     *
     * <p>Closes the loop between the two halves of a turn: {@link #findMatches}
     * <em>reports</em> what matched, {@link #applyGravityAndRefill} <em>reads</em>
     * the {@code matched} flag — but nothing public could set it, so a caller
     * outside this package couldn't get from one to the other. (The shipping
     * game does this step in its own layer, which is how the gap went unnoticed.)
     *
     * @param keys positions as {@code "row-col"} — i.e. the key set of {@link #findMatches}
     */
    public static GameBoard.Gem[][] markMatched(GameBoard.Gem[][] board, Iterable<String> keys) {
        GameBoard.Gem[][] nb = deepCopy(board);
        for (String key : keys) {
            int dash = key.indexOf('-');
            if (dash <= 0) continue;
            try {
                int r = Integer.parseInt(key.substring(0, dash));
                int c = Integer.parseInt(key.substring(dash + 1));
                if (r < 0 || r >= GameBoard.BOARD_SIZE || c < 0 || c >= GameBoard.BOARD_SIZE) continue;
                if (!nb[r][c].blocker) nb[r][c] = withMatched(nb[r][c]);
            } catch (NumberFormatException ignored) {
                // not a coordinate key — skip it rather than fail the whole board
            }
        }
        return nb;
    }

    /** Swap two positions unconditionally. Validation is the caller's job. */
    public static GameBoard.Gem[][] swapGems(GameBoard.Gem[][] board,
                                             int r1, int c1, int r2, int c2) {
        GameBoard.Gem[][] next = deepCopy(board);
        GameBoard.Gem t = next[r1][c1];
        next[r1][c1] = next[r2][c2];
        next[r2][c2] = t;
        return next;
    }

    /**
     * Fire two specials swapped into each other. The combination matrix:
     *
     * <pre>
     *   bomb   + bomb    → clear the entire board
     *   bomb   + other   → clear every gem of the partner's color
     *   wrap   + wrap    → 5x5 blast
     *   wrap   + stripe  → three full rows AND three full columns
     *   stripe + stripe  → one full row and one full column (a cross)
     * </pre>
     *
     * Blockers survive all of it.
     */
    public static GameBoard.Gem[][] combinedActivate(GameBoard.Gem[][] board,
                                                     int r1, int c1, int r2, int c2) {
        GameBoard.Gem[][] nb = deepCopy(board);
        GameBoard.SpecialType s1 = nb[r1][c1].special;
        GameBoard.SpecialType s2 = nb[r2][c2].special;

        boolean isStriped1 = s1 == GameBoard.SpecialType.STRIPED_H || s1 == GameBoard.SpecialType.STRIPED_V;
        boolean isStriped2 = s2 == GameBoard.SpecialType.STRIPED_H || s2 == GameBoard.SpecialType.STRIPED_V;

        if (s1 == GameBoard.SpecialType.COLOR_BOMB && s2 == GameBoard.SpecialType.COLOR_BOMB) {
            for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                    if (!nb[r][c].blocker) nb[r][c] = withMatched(nb[r][c]);

        } else if (s1 == GameBoard.SpecialType.COLOR_BOMB || s2 == GameBoard.SpecialType.COLOR_BOMB) {
            GameBoard.GemType absorbedType =
                (s1 == GameBoard.SpecialType.COLOR_BOMB) ? nb[r2][c2].type : nb[r1][c1].type;
            for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                    if (nb[r][c].type == absorbedType && !nb[r][c].blocker)
                        nb[r][c] = withMatched(nb[r][c]);
            // The color sweep catches the partner but not the bomb itself; consume both.
            nb[r1][c1] = withMatched(nb[r1][c1]);
            nb[r2][c2] = withMatched(nb[r2][c2]);

        } else if (s1 == GameBoard.SpecialType.WRAPPED && s2 == GameBoard.SpecialType.WRAPPED) {
            for (int r = Math.max(0, r1 - 2); r <= Math.min(GameBoard.BOARD_SIZE - 1, r1 + 2); r++)
                for (int c = Math.max(0, c1 - 2); c <= Math.min(GameBoard.BOARD_SIZE - 1, c1 + 2); c++)
                    if (!nb[r][c].blocker) nb[r][c] = withMatched(nb[r][c]);

        } else if ((s1 == GameBoard.SpecialType.WRAPPED && isStriped2)
                || (s2 == GameBoard.SpecialType.WRAPPED && isStriped1)) {
            int cr = (s1 == GameBoard.SpecialType.WRAPPED) ? r1 : r2;
            int cc = (s1 == GameBoard.SpecialType.WRAPPED) ? c1 : c2;
            for (int r = Math.max(0, cr - 1); r <= Math.min(GameBoard.BOARD_SIZE - 1, cr + 1); r++)
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                    if (!nb[r][c].blocker) nb[r][c] = withMatched(nb[r][c]);
            for (int c = Math.max(0, cc - 1); c <= Math.min(GameBoard.BOARD_SIZE - 1, cc + 1); c++)
                for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
                    if (!nb[r][c].blocker) nb[r][c] = withMatched(nb[r][c]);

        } else if (isStriped1 && isStriped2) {
            boolean h1 = s1 == GameBoard.SpecialType.STRIPED_H;
            boolean h2 = s2 == GameBoard.SpecialType.STRIPED_H;
            if (h1 && h2) {
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                    if (!nb[r1][c].blocker) nb[r1][c] = withMatched(nb[r1][c]);
                    if (!nb[r2][c].blocker) nb[r2][c] = withMatched(nb[r2][c]);
                }
            } else if (!h1 && !h2) {
                for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
                    if (!nb[r][c1].blocker) nb[r][c1] = withMatched(nb[r][c1]);
                    if (!nb[r][c2].blocker) nb[r][c2] = withMatched(nb[r][c2]);
                }
            } else {
                int rowToClear = h1 ? r1 : r2;
                int colToClear = !h1 ? c1 : c2;
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                    if (!nb[rowToClear][c].blocker) nb[rowToClear][c] = withMatched(nb[rowToClear][c]);
                for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
                    if (!nb[r][colToClear].blocker) nb[r][colToClear] = withMatched(nb[r][colToClear]);
            }
        }

        return nb;
    }

    /** Name the visual effect a special+special combination should play. */
    public static String combinedFxType(GameBoard.SpecialType s1, GameBoard.SpecialType s2) {
        if (s1 == GameBoard.SpecialType.COLOR_BOMB && s2 == GameBoard.SpecialType.COLOR_BOMB)
            return "boardClear";
        if (s1 == GameBoard.SpecialType.COLOR_BOMB || s2 == GameBoard.SpecialType.COLOR_BOMB)
            return "colorSurge";
        if (s1 == GameBoard.SpecialType.WRAPPED && s2 == GameBoard.SpecialType.WRAPPED)
            return "largeBlast";
        boolean str1 = s1 == GameBoard.SpecialType.STRIPED_H || s1 == GameBoard.SpecialType.STRIPED_V;
        boolean str2 = s2 == GameBoard.SpecialType.STRIPED_H || s2 == GameBoard.SpecialType.STRIPED_V;
        if ((s1 == GameBoard.SpecialType.WRAPPED && str2)
         || (s2 == GameBoard.SpecialType.WRAPPED && str1))
            return "largeSwipe";
        if (str1 && str2) return "crossClear";
        return "unknown";
    }

    /**
     * Clear matched gems, drop survivors, refill from the top.
     *
     * <p>Each column is rebuilt bottom-up from its survivors, which preserves their
     * relative order for free.
     *
     * @param animateFall flags new gems {@code falling} so the renderer can animate
     *                    them in — true during a cascade, false for a silent cleanup pass.
     */
    public static GameBoard.Gem[][] applyGravityAndRefill(GameBoard.Gem[][] board, boolean animateFall) {
        GameBoard.Gem[][] next = new GameBoard.Gem[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
        for (int col = 0; col < GameBoard.BOARD_SIZE; col++) {
            List<GameBoard.Gem> kept = new ArrayList<>();
            for (int row = GameBoard.BOARD_SIZE - 1; row >= 0; row--) {
                if (!board[row][col].matched) kept.add(board[row][col]);
            }
            while (kept.size() < GameBoard.BOARD_SIZE) {
                String id = "n-" + col + "-" + nextId();
                kept.add(new GameBoard.Gem(BASE_TYPES[RNG.nextInt(BASE_TYPES.length)],
                    id, false, animateFall, null, false, 0, false));
            }
            for (int row = 0; row < GameBoard.BOARD_SIZE; row++) {
                next[row][col] = kept.get(GameBoard.BOARD_SIZE - 1 - row);
            }
        }
        return next;
    }

    private BoardEngine() {}
}
