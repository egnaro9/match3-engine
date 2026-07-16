// Exposes the engine to JavaScript, compiled by TeaVM.
//
// Boundary design: boards never cross the wire. JS holds an opaque int handle
// while the Gem[][] stays in Java; only primitives go in and JSON strings come
// out. That keeps the interop surface to types @JSExport handles natively — no
// object marshalling to fight, and the engine needs no knowledge of JS at all.
package io.github.egnaro9.match3.js;

import io.github.egnaro9.match3.BoardEngine;
import io.github.egnaro9.match3.GameBoard;
import io.github.egnaro9.match3.LevelConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teavm.jso.JSExport;

public final class Match3Js {

    private static final HashMap<Integer, GameBoard.Gem[][]> BOARDS = new HashMap<>();
    private static int nextHandle = 1;

    private static int store(GameBoard.Gem[][] b) {
        int h = nextHandle++;
        BOARDS.put(h, b);
        return h;
    }

    private static GameBoard.Gem[][] get(int handle) {
        GameBoard.Gem[][] b = BOARDS.get(handle);
        if (b == null) throw new IllegalArgumentException("bad board handle: " + handle);
        return b;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @JSExport
    public static int createBoard(double ice, double chain, double blocker, String difficulty) {
        return store(BoardEngine.createBoard(new LevelConfig(ice, chain, blocker, difficulty)));
    }

    /** Boards are immutable snapshots; drop ones you're done with. */
    @JSExport
    public static void release(int handle) {
        BOARDS.remove(handle);
    }

    @JSExport
    public static int liveBoards() {
        return BOARDS.size();
    }

    // ── the engine, verbatim ──────────────────────────────────────────────────

    @JSExport
    public static boolean wouldCreateMatch(int handle, int r1, int c1, int r2, int c2) {
        return BoardEngine.wouldCreateMatch(get(handle), r1, c1, r2, c2);
    }

    @JSExport
    public static boolean hasAnyValidMove(int handle) {
        return BoardEngine.hasAnyValidMove(get(handle));
    }

    @JSExport
    public static int swapGems(int handle, int r1, int c1, int r2, int c2) {
        return store(BoardEngine.swapGems(get(handle), r1, c1, r2, c2));
    }

    @JSExport
    public static int activateSpecialGem(int handle, int row, int col) {
        return store(BoardEngine.activateSpecialGem(get(handle), row, col));
    }

    @JSExport
    public static int combinedActivate(int handle, int r1, int c1, int r2, int c2) {
        return store(BoardEngine.combinedActivate(get(handle), r1, c1, r2, c2));
    }

    /** @param csvKeys "row-col" positions, comma separated (findMatches' key set) */
    @JSExport
    public static int markMatched(int handle, String csvKeys) {
        List<String> keys = new ArrayList<>();
        for (String k : csvKeys.split(",")) {
            if (!k.isEmpty()) keys.add(k);
        }
        return store(BoardEngine.markMatched(get(handle), keys));
    }

    @JSExport
    public static int applyGravityAndRefill(int handle, boolean animateFall) {
        return store(BoardEngine.applyGravityAndRefill(get(handle), animateFall));
    }

    @JSExport
    public static String combinedFxType(String s1, String s2) {
        return BoardEngine.combinedFxType(
            GameBoard.SpecialType.fromString(s1), GameBoard.SpecialType.fromString(s2));
    }

    // ── serialization (hand-rolled — the engine has no JSON dependency) ────────

    @JSExport
    public static String boardJson(int handle) {
        GameBoard.Gem[][] b = get(handle);
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < GameBoard.BOARD_SIZE; r++) {
            if (r > 0) sb.append(',');
            sb.append('[');
            for (int c = 0; c < GameBoard.BOARD_SIZE; c++) {
                if (c > 0) sb.append(',');
                gemJson(sb, b[r][c]);
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private static void gemJson(StringBuilder sb, GameBoard.Gem g) {
        sb.append("{\"type\":\"").append(g.type.name())
          .append("\",\"id\":\"").append(g.id)
          .append("\",\"matched\":").append(g.matched)
          .append(",\"falling\":").append(g.falling)
          .append(",\"special\":").append(g.special == null ? "null" : "\"" + g.special.name() + "\"")
          .append(",\"ice\":").append(g.ice)
          .append(",\"chain\":").append(g.chain)
          .append(",\"blocker\":").append(g.blocker)
          .append('}');
    }

    @JSExport
    public static String findMatchesJson(int handle) {
        HashMap<String, BoardEngine.MatchInfo> m = BoardEngine.findMatches(get(handle));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, BoardEngine.MatchInfo> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            BoardEngine.MatchInfo mi = e.getValue();
            sb.append('"').append(e.getKey()).append("\":{\"length\":").append(mi.length)
              .append(",\"direction\":\"").append(mi.direction)
              .append("\",\"row\":").append(mi.row)
              .append(",\"col\":").append(mi.col)
              .append(",\"intersection\":").append(mi.intersection)
              .append('}');
        }
        return sb.append('}').toString();
    }

    /**
     * Every gem id on the board, so the page can assert uniqueness against the
     * live engine — the browser is the environment where a clock-derived id
     * scheme actually collides, so it's the honest place to check.
     */
    @JSExport
    public static String idsJson(int handle) {
        GameBoard.Gem[][] b = get(handle);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (GameBoard.Gem[] row : b) {
            for (GameBoard.Gem g : row) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(g.id).append('"');
            }
        }
        return sb.append(']').toString();
    }

    private Match3Js() {}
}
