package io.github.andrewwwwwwwwwwwwwww.shopguard.claim;

/**
 * The claim-boundary rule for piston-induced movement and pops, as a pure function so it can be
 * pinned down by tests that need no server, no Fabric loader and no Minecraft runtime — the mixin
 * that applies it cannot be unit-tested, for the same reasons the faction bridge cannot (see
 * {@code ClaimPermissionsTest}).
 *
 * <p>"Crosses a boundary" is decided by reference identity, not by coordinates:
 * {@code ClaimStore#claimAt} returns the same live {@link Claim} object for every position inside
 * one claim and {@code null} for unclaimed land, so two positions share a claim exactly when the
 * two lookups are reference-equal. {@code null} is wilderness — a move between two unclaimed
 * positions crosses no boundary, so vanilla behavior holds there untouched.
 */
public final class ClaimBoundaries {
    private ClaimBoundaries() {}

    /**
     * True when a piston-induced movement or pop from the position whose claim is {@code origin}
     * to the position whose claim is {@code destination} crosses a claim boundary and must be
     * cancelled. Both sides unclaimed is vanilla territory (allowed); a move between two different
     * claims, or between a claim and the wilderness, crosses (cancelled).
     */
    public static boolean crossesBoundary(Claim origin, Claim destination) {
        return origin != destination;
    }
}
