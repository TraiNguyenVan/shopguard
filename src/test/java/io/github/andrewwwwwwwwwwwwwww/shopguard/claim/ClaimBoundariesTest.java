package io.github.andrewwwwwwwwwwwwwww.shopguard.claim;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary rule a piston's pop must obey, as pure cases. The mixin applies the rule; these
 * tests pin the rule itself, so the piston-pop regression — a pop inside a claim being cancelled
 * outright instead of being boundary-checked, which killed every piston sugar-cane farm inside a
 * claim — cannot come back silently.
 *
 * <p>The last test pins the assumption the rule (and the mixin's push loop with it) rests on:
 * {@code ClaimStore#claimAt} must hand out one live object per claim. If that ever became a
 * per-lookup copy, reference equality would silently stop meaning "same claim".
 */
class ClaimBoundariesTest {
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void aPopEntirelyInsideOneClaimCrossesNoBoundary() {
        Claim farm = new Claim(1, OWNER, "owner", "minecraft:overworld");
        assertFalse(ClaimBoundaries.crossesBoundary(farm, farm));
    }

    @Test
    void wildernessToWildernessIsVanillaTerritory() {
        assertFalse(ClaimBoundaries.crossesBoundary(null, null));
    }

    @Test
    void aPistonOutsideAClaimCannotPopIntoIt() {
        Claim farm = new Claim(1, OWNER, "owner", "minecraft:overworld");
        assertTrue(ClaimBoundaries.crossesBoundary(null, farm));
    }

    @Test
    void aPistonInsideAClaimCannotPopOutOfIt() {
        Claim farm = new Claim(1, OWNER, "owner", "minecraft:overworld");
        assertTrue(ClaimBoundaries.crossesBoundary(farm, null));
    }

    @Test
    void aPopBetweenTwoDifferentClaimsCrosses() {
        Claim a = new Claim(1, OWNER, "owner", "minecraft:overworld");
        Claim b = new Claim(2, OWNER, "owner", "minecraft:overworld");
        assertTrue(ClaimBoundaries.crossesBoundary(a, b));
    }

    @Test
    void claimAtReturnsTheSameLiveObjectForEveryPositionInsideOneClaim() {
        ClaimStore store = new ClaimStore();
        Claim farm = store.newClaim(OWNER, "owner", "minecraft:overworld");
        farm.shape.addRect(0, 0, 9, 9);
        assertSame(store.claimAt("minecraft:overworld", 0, 0),
                store.claimAt("minecraft:overworld", 9, 9));
        assertNull(store.claimAt("minecraft:overworld", 10, 0));
        assertNull(store.claimAt("minecraft:nether", 0, 0));
    }
}
