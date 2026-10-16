package com.evanchubbuck.blindstone;

import java.util.BitSet;

/** Immutable authorization for exactly 4096 blocks, indexed in vanilla X/Z/Y order. */
public final class SectionVisibility {
    public static final int BLOCK_COUNT = 4096;
    private static final SectionVisibility NONE = new SectionVisibility(new BitSet());
    private final BitSet revealed;

    private SectionVisibility(BitSet revealed) {
        if (revealed.length() > BLOCK_COUNT) throw new IllegalArgumentException("Mask exceeds a section");
        this.revealed = (BitSet) revealed.clone();
    }

    public static SectionVisibility none() { return NONE; }

    public static SectionVisibility from(BitSet revealed) { return new SectionVisibility(revealed); }

    public static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IllegalArgumentException("Local coordinates must be in [0, 15]");
        }
        return (y << 8) | (z << 4) | x;
    }

    public boolean isRevealed(int x, int y, int z) { return revealed.get(index(x, y, z)); }

    public int count() { return revealed.cardinality(); }

    public SectionVisibility union(SectionVisibility other) {
        BitSet combined = (BitSet) revealed.clone();
        combined.or(other.revealed);
        return new SectionVisibility(combined);
    }
}
