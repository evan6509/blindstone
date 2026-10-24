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

    /** Iterate authorized indices without copying or exposing mutable mask storage. */
    public int nextRevealedIndex(int fromIndex) { return revealed.nextSetBit(fromIndex); }

    @Override public boolean equals(Object other) {
        return this==other || other instanceof SectionVisibility mask && revealed.equals(mask.revealed);
    }
    @Override public int hashCode() { return revealed.hashCode(); }

    public int count() { return revealed.cardinality(); }

    public SectionVisibility union(SectionVisibility other) {
        if (other.count() == 0 || this == other) return this;
        if (count() == 0) return other;
        BitSet combined = (BitSet) revealed.clone();
        combined.or(other.revealed);
        return new SectionVisibility(combined);
    }

    public SectionVisibility subtract(SectionVisibility other) {
        if (other.count() == 0 || count() == 0) return this;
        BitSet remaining = (BitSet) revealed.clone();
        remaining.andNot(other.revealed);
        return remaining.isEmpty() ? NONE : new SectionVisibility(remaining);
    }
}
