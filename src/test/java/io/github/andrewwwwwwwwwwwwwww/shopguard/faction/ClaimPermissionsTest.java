package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anarchism's {@code Vô chính phủ} as rules: three refusals, each one a pure function of a party id.
 *
 * <p>The point of testing them here rather than through {@link PlayerFactions} is that these are the rules.
 * {@code PlayerFactions} only supplies the id, and testing it needs a server, a Fabric loader and an
 * EconomyCraft install — so a version of this suite that went through the bridge would simply not run in
 * this repository.
 */
class ClaimPermissionsTest {

    @Test
    void anarchismMayNotClaim() {
        assertFalse(ClaimPermissions.mayClaim(FactionIds.ANARCHISM));
    }

    @Test
    void anarchismMayNotReceiveAClaim() {
        assertFalse(ClaimPermissions.mayReceiveTransfer(FactionIds.ANARCHISM));
    }

    @Test
    void anarchismMayNotBeTrusted() {
        assertFalse(ClaimPermissions.mayBeTrusted(FactionIds.ANARCHISM));
    }

    @Test
    void everyOtherPartyIsUnrestricted() {
        for (String party : new String[]{FactionIds.COMMUNISM, FactionIds.CAPITALISM, FactionIds.MONARCHY}) {
            assertTrue(ClaimPermissions.mayClaim(party), party);
            assertTrue(ClaimPermissions.mayReceiveTransfer(party), party);
            assertTrue(ClaimPermissions.mayBeTrusted(party), party);
        }
    }

    @Test
    void noPartySystemMeansNoRestrictionAtAll() {
        // The single most important case: with EconomyCraft absent there is no party, and ShopGuard has to
        // behave exactly as it did before any of this existed.
        assertTrue(ClaimPermissions.mayClaim(null));
        assertTrue(ClaimPermissions.mayReceiveTransfer(null));
        assertTrue(ClaimPermissions.mayBeTrusted(null));
    }

    @Test
    void anUnknownPartyIsTreatedAsUnrestrictedRatherThanAsLockedOut() {
        // A party this build has never heard of must not lock every player out of claiming. Getting this
        // wrong fails in one direction only: a player who should have been restricted is not.
        assertTrue(ClaimPermissions.mayClaim("anarchism "));
        assertTrue(ClaimPermissions.mayClaim("Anarchism"));
        assertTrue(ClaimPermissions.mayClaim("somethingelse"));
        assertTrue(ClaimPermissions.mayReceiveTransfer("somethingelse"));
        assertTrue(ClaimPermissions.mayBeTrusted("somethingelse"));
    }

    @Test
    void theRulesAreCaseSensitiveAboutTheIdTheyCompare() {
        // Not a style preference: it is what makes the unknown-id case above fail open rather than fail
        // shut. Pinned so a future "be lenient" change is a deliberate edit to this file.
        assertFalse("Anarchism".equals(FactionIds.ANARCHISM));
        assertTrue("anarchism".equals(FactionIds.ANARCHISM));
    }
}
