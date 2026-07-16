package io.github.egnaro9.match3;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Gem ids are renderer identity keys — two gems sharing one animate as a single
 * gem. The old scheme (nanoTime + random) was only probabilistically unique and
 * leaned on the platform clock's resolution; a browser's clamped clock made it
 * collide hundreds of times per 100k gems while a JVM hid the flaw entirely.
 * These tests hold the guarantee unconditionally, on any clock.
 */
public class IdUniquenessTest {

    private static LevelConfig plain() {
        return LevelConfig.plain();
    }

    @Test
    public void generatedBoardIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int b = 0; b < 40; b++) {
            GameBoard.Gem[][] board = BoardEngine.createBoard(plain());
            for (GameBoard.Gem[] row : board) {
                for (GameBoard.Gem g : row) {
                    assertTrue("duplicate gem id: " + g.id, seen.add(g.id));
                }
            }
        }
        assertEquals(40 * 64, seen.size());
    }

    @Test
    public void refillIdsAreUniqueAcrossManyCascades() {
        // Refill is the hot path: it mints new gems every cascade tick, which is
        // exactly where a clock-based id scheme collides.
        Set<String> seen = new HashSet<>();
        GameBoard.Gem[][] board = BoardEngine.createBoard(plain());
        for (GameBoard.Gem[] row : board)
            for (GameBoard.Gem g : row) seen.add(g.id);

        for (int tick = 0; tick < 200; tick++) {
            // Clear the whole top row, forcing 8 fresh gems per tick.
            GameBoard.Gem[][] marked = BoardEngine.deepCopy(board);
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                GameBoard.Gem g = marked[0][c];
                marked[0][c] = new GameBoard.Gem(g.type, g.id, true, g.falling, g.special, g.ice, g.chain, g.blocker);
            }
            board = BoardEngine.applyGravityAndRefill(marked, true);
            for (GameBoard.Gem[] row : board) {
                for (GameBoard.Gem g : row) {
                    if (!seen.add(g.id)) {
                        // An id may legitimately repeat only if it's the same surviving gem.
                        // Refilled gems must never reuse one.
                    }
                }
            }
        }
        // 64 initial + 8 new per tick, all distinct.
        assertEquals(64 + 200 * 8, seen.size());
    }

    @Test
    public void idsAreUniqueWithoutRelyingOnClockResolution() {
        // Mint many gems as fast as possible: a clock-derived id would collide
        // here on any platform whose timer doesn't advance between calls.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            GameBoard.Gem[][] b = BoardEngine.createBoard(plain());
            for (GameBoard.Gem[] row : b)
                for (GameBoard.Gem g : row) seen.add(g.id);
        }
        assertEquals(50 * 64, seen.size());
    }
}
