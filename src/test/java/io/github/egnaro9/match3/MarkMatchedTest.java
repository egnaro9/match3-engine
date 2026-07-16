package io.github.egnaro9.match3;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashMap;
import org.junit.Test;

/** markMatched closes findMatches -> applyGravityAndRefill for outside callers. */
public class MarkMatchedTest {

    private static GameBoard.Gem[][] boardOf(GameBoard.GemType fill) {
        GameBoard.Gem[][] b = new GameBoard.Gem[GameBoard.BOARD_SIZE][GameBoard.BOARD_SIZE];
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++)
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++)
                b[r][c] = GameBoard.Gem.create(
                    (r + c) % 2 == 0 ? GameBoard.GemType.RED : GameBoard.GemType.BLUE, r + "-" + c);
        return b;
    }

    @Test
    public void marksOnlyTheGivenPositions() {
        GameBoard.Gem[][] b = boardOf(GameBoard.GemType.RED);
        GameBoard.Gem[][] out = BoardEngine.markMatched(b, Arrays.asList("0-0", "3-4"));
        assertTrue(out[0][0].matched);
        assertTrue(out[3][4].matched);
        assertFalse(out[1][1].matched);
    }

    @Test
    public void doesNotMutateInput() {
        GameBoard.Gem[][] b = boardOf(GameBoard.GemType.RED);
        BoardEngine.markMatched(b, Arrays.asList("0-0"));
        assertFalse("input board must be untouched", b[0][0].matched);
    }

    @Test
    public void blockersAreNeverMarked() {
        GameBoard.Gem[][] b = boardOf(GameBoard.GemType.RED);
        b[2][2] = new GameBoard.Gem(GameBoard.GemType.BLOCKER, "blk", false, false, null, false, 0, true);
        GameBoard.Gem[][] out = BoardEngine.markMatched(b, Arrays.asList("2-2"));
        assertFalse(out[2][2].matched);
    }

    @Test
    public void ignoresGarbageAndOutOfRangeKeys() {
        GameBoard.Gem[][] b = boardOf(GameBoard.GemType.RED);
        GameBoard.Gem[][] out = BoardEngine.markMatched(b, Arrays.asList("", "x-y", "99-0", "0-99", "-", "0-0"));
        assertTrue(out[0][0].matched); // the one valid key still applied
    }

    @Test
    public void roundTripsFindMatchesIntoGravity() {
        // A real 3-in-a-row, marked via findMatches' own keys, then collapsed.
        GameBoard.Gem[][] b = boardOf(GameBoard.GemType.RED);
        for (int c = 0; c < 3; c++) b[0][c] = GameBoard.Gem.create(GameBoard.GemType.GREEN, "g" + c);
        HashMap<String, BoardEngine.MatchInfo> m = BoardEngine.findMatches(b);
        assertFalse("fixture should contain a match", m.isEmpty());
        GameBoard.Gem[][] marked = BoardEngine.markMatched(b, m.keySet());
        GameBoard.Gem[][] after = BoardEngine.applyGravityAndRefill(marked, true);
        for (GameBoard.Gem[] row : after)
            for (GameBoard.Gem g : row) assertFalse("nothing stays matched after refill", g.matched);
    }
}
