package io.github.egnaro9.match3;

/**
 * Board-generation parameters.
 *
 * <p>In the shipping game these arrive as JSON from the level definition. The
 * engine takes them as a plain value type instead, which is what keeps it free
 * of any JSON or platform dependency — the serialization boundary belongs to
 * the caller, not the rules.
 */
public final class LevelConfig {

    /** Per-cell probability (0..1) of spawning with an ice layer. */
    public final double iceProbability;
    /** Per-cell probability (0..1) of spawning chained. */
    public final double chainProbability;
    /** Per-cell probability (0..1) of spawning as an immovable blocker. */
    public final double blockerProbability;
    /** "easy" | "normal" | "hard" | "expert" | "master" — raises the double-chain rate at hard+. */
    public final String difficulty;

    public LevelConfig(double iceProbability, double chainProbability,
                       double blockerProbability, String difficulty) {
        this.iceProbability     = iceProbability;
        this.chainProbability   = chainProbability;
        this.blockerProbability = blockerProbability;
        this.difficulty         = difficulty;
    }

    /** No obstacles, normal difficulty — a plain board. */
    public static LevelConfig plain() {
        return new LevelConfig(0.0, 0.0, 0.0, "normal");
    }

    boolean isHardPlus() {
        return "hard".equals(difficulty) || "expert".equals(difficulty) || "master".equals(difficulty);
    }
}
