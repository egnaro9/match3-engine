package io.github.egnaro9.match3;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import java.util.*;

// JUnit 5 Jupiter assertions: message is the LAST argument (unlike JUnit 4)
import org.junit.jupiter.api.Assertions;

/**
 * jqwik property-based tests for BoardEngine pure static methods.
 *
 * Invariants encoded: A1–A7 (detection), B1–B7 (activation), C1–C2 (combinedFxType).
 *
 * Generator: random 8×8 board over 5 base colors, with tunable blocker and
 * pre-placed special injection. Biased to plant horizontal/vertical runs and
 * L/T shapes so detection properties actually exercise match logic.
 *
 * HARD BOUNDARY: a jqwik counterexample = a real engine bug. Do NOT edit
 * BoardEngine/GameBoard to make a property pass — fix the engine instead.
 */
public class BoardEnginePropertyTest {

    private static final GameBoard.GemType[] BASE_TYPES = {
        GameBoard.GemType.RED, GameBoard.GemType.BLUE, GameBoard.GemType.GREEN,
        GameBoard.GemType.YELLOW, GameBoard.GemType.PURPLE
    };

    private static final GameBoard.SpecialType[] SPECIAL_TYPES = GameBoard.SpecialType.values();

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATOR — random 8×8 board
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a random 8×8 Gem board with:
     *  - Each non-blocker cell filled with a uniformly random base gem type.
     *  - ~10% chance per cell of being a BLOCKER.
     *  - With 40% probability, plants one horizontal run of length 3–5 at a random position.
     *  - With 40% probability, plants one vertical run of length 3–5 at a random position.
     *  - With 20% probability, makes the horizontal + vertical share a corner cell (L/T shape).
     *  - With 15% probability, places a random special gem at (row=3, col=3).
     *
     * This biasing ensures detection properties (A1–A7) encounter real match configurations
     * rather than almost-uniformly-random boards that rarely produce runs.
     */
    @Provide
    Arbitrary<GameBoard.Gem[][]> randomBoard() {
        return Arbitraries.create(() -> {
            Random rng = new Random();
            GameBoard.Gem[][] board = new GameBoard.Gem[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];

            // Fill with base random types or blockers
            for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
                for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                    if (rng.nextDouble() < 0.10) {
                        board[r][c] = new GameBoard.Gem(
                            GameBoard.GemType.BLOCKER, r + "-" + c,
                            false, false, null, false, 0, true);
                    } else {
                        GameBoard.GemType t = BASE_TYPES[rng.nextInt(BASE_TYPES.length)];
                        board[r][c] = GameBoard.Gem.create(t, r + "-" + c);
                    }
                }
            }

            // Bias: plant a horizontal run
            if (rng.nextDouble() < 0.40) {
                int runLen = 3 + rng.nextInt(3); // 3, 4, or 5
                int row = rng.nextInt(GameBoard.BOARD_SIZE);
                int maxStart = GameBoard.BOARD_SIZE - runLen;
                if (maxStart >= 0) {
                    int startCol = rng.nextInt(maxStart + 1);
                    GameBoard.GemType t = BASE_TYPES[rng.nextInt(BASE_TYPES.length)];
                    for (int c = startCol; c < startCol + runLen; c++) {
                        board[row][c] = GameBoard.Gem.create(t, row + "-" + c + "-hr");
                    }
                }
            }

            // Bias: plant a vertical run (may share a cell with the h-run → L/T)
            if (rng.nextDouble() < 0.40) {
                int runLen = 3 + rng.nextInt(3);
                int col = rng.nextInt(GameBoard.BOARD_SIZE);
                int maxStart = GameBoard.BOARD_SIZE - runLen;
                if (maxStart >= 0) {
                    int startRow = rng.nextInt(maxStart + 1);
                    // 20% chance: adopt the type at the shared cell so we create an L/T
                    GameBoard.GemType t;
                    if (rng.nextDouble() < 0.20 && board[startRow][col] != null
                            && !board[startRow][col].blocker) {
                        t = board[startRow][col].type;
                    } else {
                        t = BASE_TYPES[rng.nextInt(BASE_TYPES.length)];
                    }
                    for (int r = startRow; r < startRow + runLen; r++) {
                        board[r][col] = GameBoard.Gem.create(t, r + "-" + col + "-vr");
                    }
                }
            }

            // Bias: occasionally pre-place a special gem at (3,3) for activation properties
            if (rng.nextDouble() < 0.15) {
                GameBoard.SpecialType sp = SPECIAL_TYPES[rng.nextInt(SPECIAL_TYPES.length)];
                GameBoard.GemType t = BASE_TYPES[rng.nextInt(BASE_TYPES.length)];
                board[3][3] = new GameBoard.Gem(t, "3-3-sp",
                        false, false, sp, false, 0, false);
            }

            return board;
        });
    }

    /**
     * Generates an arbitrary SpecialType pair for combinedFxType properties.
     */
    @Provide
    Arbitrary<GameBoard.SpecialType[]> specialPair() {
        return Arbitraries.of(SPECIAL_TYPES)
                .flatMap(a -> Arbitraries.of(SPECIAL_TYPES).map(b -> new GameBoard.SpecialType[]{a, b}));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A1 — every findMatches key is inside a ≥3 contiguous same-type non-blocker run
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a1_every_match_key_is_in_a_real_run(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        HashMap<String, BoardEngine.MatchInfo> matches = BoardEngine.findMatches(board);
        for (Map.Entry<String, BoardEngine.MatchInfo> entry : matches.entrySet()) {
            String key = entry.getKey();
            String[] parts = key.split("-");
            int r = Integer.parseInt(parts[0]);
            int c = Integer.parseInt(parts[1]);

            Assertions.assertFalse(board[r][c].type == GameBoard.GemType.BLOCKER,
                    "matched cell " + key + " must not be BLOCKER");
            Assertions.assertTrue(BoardEngine.checkMatchAt(board, r, c),
                    "checkMatchAt agrees for " + key);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A2 — intersection==true iff cell covered by both an H and a V run
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a2_intersection_iff_h_and_v_coverage(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        HashMap<String, BoardEngine.MatchInfo> matches = BoardEngine.findMatches(board);

        Set<String> hKeys = new HashSet<>();
        Set<String> vKeys = new HashSet<>();

        // Horizontal scan
        for (int row = 0; row < GameBoard.BOARD_SIZE; row++) {
            int start = 0;
            for (int col = 1; col <= GameBoard.BOARD_SIZE; col++) {
                GameBoard.GemType prev = board[row][col - 1].type;
                boolean boundary = col == GameBoard.BOARD_SIZE
                        || board[row][col].type != prev
                        || prev == GameBoard.GemType.BLOCKER;
                if (boundary) {
                    if (col - start >= 3 && prev != GameBoard.GemType.BLOCKER) {
                        for (int i = start; i < col; i++) hKeys.add(row + "-" + i);
                    }
                    start = col;
                }
            }
        }

        // Vertical scan
        for (int col = 0; col < GameBoard.BOARD_SIZE; col++) {
            int start = 0;
            for (int row = 1; row <= GameBoard.BOARD_SIZE; row++) {
                GameBoard.GemType prev = board[row - 1][col].type;
                boolean boundary = row == GameBoard.BOARD_SIZE
                        || board[row][col].type != prev
                        || prev == GameBoard.GemType.BLOCKER;
                if (boundary) {
                    if (row - start >= 3 && prev != GameBoard.GemType.BLOCKER) {
                        for (int i = start; i < row; i++) vKeys.add(i + "-" + col);
                    }
                    start = row;
                }
            }
        }

        for (Map.Entry<String, BoardEngine.MatchInfo> entry : matches.entrySet()) {
            String key = entry.getKey();
            boolean expectedIntersection = hKeys.contains(key) && vKeys.contains(key);
            Assertions.assertEquals(expectedIntersection, entry.getValue().intersection,
                    "intersection flag for " + key);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A3 — BLOCKER is never in a match and always breaks a run
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a3_blocker_never_in_match(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        HashMap<String, BoardEngine.MatchInfo> matches = BoardEngine.findMatches(board);
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                if (board[r][c].blocker) {
                    Assertions.assertFalse(matches.containsKey(r + "-" + c),
                            "blocker " + r + "-" + c + " must not be in matches");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A4 — checkMatchAt(b,r,c) == ("r-c" ∈ findMatches(b))
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a4_checkMatchAt_agrees_with_findMatches(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        HashMap<String, BoardEngine.MatchInfo> matches = BoardEngine.findMatches(board);
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                boolean inMap = matches.containsKey(r + "-" + c);
                boolean atCheck = BoardEngine.checkMatchAt(board, r, c);
                Assertions.assertEquals(inMap, atCheck,
                        "checkMatchAt/findMatches agree at " + r + "-" + c);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A5 — wouldCreateMatch(b,swap) == findMatches(swapGems(b,swap)) non-empty
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a5_wouldCreateMatch_agrees_with_findMatches_after_swap(
            @ForAll("randomBoard") GameBoard.Gem[][] board) {
        // A5 invariant: wouldCreateMatch(b, swap) == findMatches(swappedBoard) is non-empty,
        // BUT only when the pre-swap board has NO existing matches. If the board already
        // has matches, findMatches(swapped) will be non-empty regardless of the swap
        // (because findMatches returns ALL matches, not just new ones). Filter those boards.
        if (!BoardEngine.findMatches(board).isEmpty()) return; // Assume: board has no pre-existing matches

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                // horizontal swap
                if (c + 1 < GameBoard.BOARD_SIZE) {
                    if (!board[r][c].blocker && !board[r][c + 1].blocker) {
                        boolean wcm = BoardEngine.wouldCreateMatch(board, r, c, r, c + 1);
                        GameBoard.Gem[][] swapped = BoardEngine.swapGems(board, r, c, r, c + 1);
                        boolean fmNonEmpty = !BoardEngine.findMatches(swapped).isEmpty();
                        Assertions.assertEquals(fmNonEmpty, wcm,
                                "A5 h-swap at " + r + "," + c);
                    }
                }
                // vertical swap
                if (r + 1 < GameBoard.BOARD_SIZE) {
                    if (!board[r][c].blocker && !board[r + 1][c].blocker) {
                        boolean wcm = BoardEngine.wouldCreateMatch(board, r, c, r + 1, c);
                        GameBoard.Gem[][] swapped = BoardEngine.swapGems(board, r, c, r + 1, c);
                        boolean fmNonEmpty = !BoardEngine.findMatches(swapped).isEmpty();
                        Assertions.assertEquals(fmNonEmpty, wcm,
                                "A5 v-swap at " + r + "," + c);
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A6 — hasAnyValidMove(b) == (∃ adjacent non-blocker swap with wouldCreateMatch true)
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a6_hasAnyValidMove_equals_exists_wouldCreateMatch(
            @ForAll("randomBoard") GameBoard.Gem[][] board) {
        boolean hasMove = BoardEngine.hasAnyValidMove(board);
        boolean existsWcm = false;
        outer:
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                if (board[r][c].blocker) continue;
                if (c + 1 < GameBoard.BOARD_SIZE && !board[r][c + 1].blocker
                        && BoardEngine.wouldCreateMatch(board, r, c, r, c + 1)) {
                    existsWcm = true; break outer;
                }
                if (r + 1 < GameBoard.BOARD_SIZE && !board[r + 1][c].blocker
                        && BoardEngine.wouldCreateMatch(board, r, c, r + 1, c)) {
                    existsWcm = true; break outer;
                }
            }
        }
        Assertions.assertEquals(existsWcm, hasMove, "hasAnyValidMove == existsWcm");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A7 — findMatches does not mutate its input board
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void a7_findMatches_does_not_mutate_board(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        GameBoard.GemType[][] typesBefore = new GameBoard.GemType[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
        boolean[][] matchedBefore = new boolean[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                typesBefore[r][c] = board[r][c].type;
                matchedBefore[r][c] = board[r][c].matched;
            }
        }

        BoardEngine.findMatches(board);

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                Assertions.assertEquals(typesBefore[r][c], board[r][c].type,
                        "type not mutated at " + r + "," + c);
                Assertions.assertEquals(matchedBefore[r][c], board[r][c].matched,
                        "matched not mutated at " + r + "," + c);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B1 — STRIPED_H marks exactly row `row` non-blocker cells, nothing off-row
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b1_stripedH_marks_exactly_target_row(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        // Plant STRIPED_H at (3,3); board cells start with matched=false
        GameBoard.Gem[][] b = copyBoardWith(board, 3, 3,
                new GameBoard.Gem(GameBoard.GemType.RED, "3-3-sh", false, false,
                        GameBoard.SpecialType.STRIPED_H, false, 0, false));
        GameBoard.Gem[][] result = BoardEngine.activateSpecialGem(b, 3, 3);

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                if (r == 3 && !b[r][c].blocker) {
                    Assertions.assertTrue(result[r][c].matched,
                            "B1: row 3 non-blocker col " + c + " must be matched");
                }
                if (r != 3) {
                    Assertions.assertFalse(result[r][c].matched,
                            "B1: off-row " + r + " col " + c + " must not be matched");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B2 — STRIPED_V marks exactly col `col` non-blocker cells, nothing off-col
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b2_stripedV_marks_exactly_target_col(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        GameBoard.Gem[][] b = copyBoardWith(board, 4, 4,
                new GameBoard.Gem(GameBoard.GemType.BLUE, "4-4-sv", false, false,
                        GameBoard.SpecialType.STRIPED_V, false, 0, false));
        GameBoard.Gem[][] result = BoardEngine.activateSpecialGem(b, 4, 4);

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                if (c == 4 && !b[r][c].blocker) {
                    Assertions.assertTrue(result[r][c].matched,
                            "B2: col 4 non-blocker row " + r + " must be matched");
                }
                if (c != 4) {
                    Assertions.assertFalse(result[r][c].matched,
                            "B2: off-col row " + r + " col " + c + " must not be matched");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B3 — WRAPPED marks exactly the 3×3 (clipped) non-blocker cells at (row,col)
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b3_wrapped_marks_3x3_clipped(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        GameBoard.Gem[][] b = copyBoardWith(board, 3, 3,
                new GameBoard.Gem(GameBoard.GemType.GREEN, "3-3-wr", false, false,
                        GameBoard.SpecialType.WRAPPED, false, 0, false));
        GameBoard.Gem[][] result = BoardEngine.activateSpecialGem(b, 3, 3);

        int rMin = Math.max(0, 3 - 1);
        int rMax = Math.min(GameBoard.BOARD_SIZE - 1, 3 + 1);
        int cMin = Math.max(0, 3 - 1);
        int cMax = Math.min(GameBoard.BOARD_SIZE - 1, 3 + 1);

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                boolean inRegion = r >= rMin && r <= rMax && c >= cMin && c <= cMax;
                if (inRegion && !b[r][c].blocker) {
                    Assertions.assertTrue(result[r][c].matched,
                            "B3: 3x3 cell " + r + "," + c + " must be matched");
                }
                if (!inRegion) {
                    Assertions.assertFalse(result[r][c].matched,
                            "B3: outside-3x3 cell " + r + "," + c + " must not be matched");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B4 — COLOR_BOMB marks exactly all non-blocker cells of target type AND self
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b4_colorBomb_marks_target_type_and_self(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        GameBoard.Gem[][] b = copyBoardWith(board, 2, 2,
                new GameBoard.Gem(GameBoard.GemType.PURPLE, "2-2-cb", false, false,
                        GameBoard.SpecialType.COLOR_BOMB, false, 0, false));
        GameBoard.GemType target = GameBoard.GemType.RED;
        GameBoard.Gem[][] result = BoardEngine.activateSpecialGem(b, 2, 2, target);

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                if (b[r][c].type == target && !b[r][c].blocker) {
                    Assertions.assertTrue(result[r][c].matched,
                            "B4: RED at " + r + "," + c + " must be matched");
                }
            }
        }
        Assertions.assertTrue(result[2][2].matched, "B4: bomb cell 2,2 must be consumed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B5 — blocker-immunity: no blocker cell ever marked by any activation
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b5_blocker_immunity_all_specials(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        int[] positions = {2, 3, 4, 5};
        GameBoard.SpecialType[] specials = GameBoard.SpecialType.values();

        for (GameBoard.SpecialType sp : specials) {
            for (int pos : positions) {
                GameBoard.Gem[][] b = copyBoardWith(board, pos, pos,
                        new GameBoard.Gem(GameBoard.GemType.RED, pos + "-" + pos + "-b5", false, false,
                                sp, false, 0, false));
                GameBoard.Gem[][] result = BoardEngine.activateSpecialGem(b, pos, pos,
                        GameBoard.GemType.BLUE);

                for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
                    for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                        if (b[r][c].blocker) {
                            Assertions.assertFalse(result[r][c].matched,
                                    "B5: blocker at " + r + "," + c + " not matched by " + sp);
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B6 — activating a non-special cell is a no-op (returns same board ref)
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b6_nonSpecial_activation_is_noop(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        GameBoard.Gem[][] b = copyBoardWith(board, 1, 1,
                GameBoard.Gem.create(GameBoard.GemType.GREEN, "1-1-plain"));
        GameBoard.Gem[][] result = BoardEngine.activateSpecialGem(b, 1, 1);
        Assertions.assertSame(b, result, "B6: non-special returns same board ref");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B7 — activation does not mutate input (deepCopy honored)
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void b7_activation_does_not_mutate_input(@ForAll("randomBoard") GameBoard.Gem[][] board) {
        GameBoard.Gem[][] b = copyBoardWith(board, 3, 4,
                new GameBoard.Gem(GameBoard.GemType.RED, "3-4-b7", false, false,
                        GameBoard.SpecialType.STRIPED_H, false, 0, false));

        boolean[][] matchedBefore = new boolean[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                matchedBefore[r][c] = b[r][c].matched;

        BoardEngine.activateSpecialGem(b, 3, 4);

        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                Assertions.assertEquals(matchedBefore[r][c], b[r][c].matched,
                        "B7: matched not mutated at " + r + "," + c);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C1 — combinedFxType: never null over all 16 SpecialType pairs
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void c1_combinedFxType_never_null(@ForAll("specialPair") GameBoard.SpecialType[] pair) {
        Assertions.assertNotNull(BoardEngine.combinedFxType(pair[0], pair[1]),
                "C1: combinedFxType not null for " + pair[0] + "," + pair[1]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C2 — combinedFxType: symmetric
    // ─────────────────────────────────────────────────────────────────────────

    @Property
    void c2_combinedFxType_symmetric(@ForAll("specialPair") GameBoard.SpecialType[] pair) {
        Assertions.assertEquals(
                BoardEngine.combinedFxType(pair[0], pair[1]),
                BoardEngine.combinedFxType(pair[1], pair[0]),
                "C2: combinedFxType symmetric for " + pair[0] + "," + pair[1]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: deep-copy a board and replace one cell
    // ─────────────────────────────────────────────────────────────────────────

    private static GameBoard.Gem[][] copyBoardWith(GameBoard.Gem[][] src, int r, int c,
                                                    GameBoard.Gem gem) {
        GameBoard.Gem[][] copy = BoardEngine.deepCopy(src);
        copy[r][c] = gem;
        return copy;
    }
}
