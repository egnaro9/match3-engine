// Board schema: the value types the engine operates on.
package io.github.egnaro9.match3;

public final class GameBoard {

    public static final int BOARD_SIZE = 8;

    /** The five playable colors, plus an immovable obstacle type. */
    public enum GemType {
        RED, BLUE, GREEN, YELLOW, PURPLE, BLOCKER;

        public static GemType fromString(String type) {
            switch (type) {
                case "red":     return RED;
                case "blue":    return BLUE;
                case "green":   return GREEN;
                case "yellow":  return YELLOW;
                case "purple":  return PURPLE;
                case "blocker": return BLOCKER;
                default: throw new IllegalArgumentException("Unknown gem type: " + type);
            }
        }
    }

    /** Power-ups a match can produce. */
    public enum SpecialType {
        STRIPED_H, STRIPED_V, WRAPPED, COLOR_BOMB;

        public static SpecialType fromString(String type) {
            switch (type) {
                case "striped_h":  return STRIPED_H;
                case "striped_v":  return STRIPED_V;
                case "wrapped":    return WRAPPED;
                case "color_bomb": return COLOR_BOMB;
                default: throw new IllegalArgumentException("Unknown special type: " + type);
            }
        }
    }

    /**
     * An immutable cell. The board is a {@code Gem[row][col]} grid.
     *
     * <p>Immutability is the reason {@link BoardEngine#deepCopy} can be a per-row
     * {@code System.arraycopy}: no gem can be mutated out from under a copy, so
     * speculative moves ("would this swap match?") are cheap and side-effect free.
     */
    public static final class Gem {
        public final GemType type;
        public final String id;
        public final boolean matched;
        public final boolean falling;
        public final SpecialType special; // null = an ordinary gem
        public final boolean ice;
        public final int chain;           // 0 = none, 1 = single layer, 2 = double layer
        public final boolean blocker;

        public Gem(GemType type, String id, boolean matched, boolean falling,
                   SpecialType special, boolean ice, int chain, boolean blocker) {
            this.type    = type;
            this.id      = id;
            this.matched = matched;
            this.falling = falling;
            this.special = special;
            this.ice     = ice;
            this.chain   = chain;
            this.blocker = blocker;
        }

        /** An ordinary gem: not matched, not falling, no special, no obstacle state. */
        public static Gem create(GemType type, String id) {
            return new Gem(type, id, false, false, null, false, 0, false);
        }
    }

    private GameBoard() {}
}
