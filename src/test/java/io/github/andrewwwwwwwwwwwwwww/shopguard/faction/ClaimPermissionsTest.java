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

    @Test
    void aPlayerWhoHasChosenNothingIsUnrestrictedEvenThoughTheirPartyIdIsAnarchism() {
        // The regression this two-argument form exists for. EconomyCraft's default party is Anarchism, so on
        // a server where nobody has run /eco party every player reports "anarchism" while having chosen
        // nothing. Gating on the id alone would freeze claiming, trusting and transfers server-wide —
        // operators included — for having never opened a menu.
        assertTrue(ClaimPermissions.mayClaim(FactionIds.ANARCHISM, false));
        assertTrue(ClaimPermissions.mayReceiveTransfer(FactionIds.ANARCHISM, false));
        assertTrue(ClaimPermissions.mayBeTrusted(FactionIds.ANARCHISM, false));
    }

    @Test
    void optingIntoAnarchismStillRefusesAllThree() {
        // The other half of the pair: the gate must not become a way to keep the rules from ever applying.
        // Once a player has chosen, hasChosen is true and the strict single-argument rules apply as before.
        assertFalse(ClaimPermissions.mayClaim(FactionIds.ANARCHISM, true));
        assertFalse(ClaimPermissions.mayReceiveTransfer(FactionIds.ANARCHISM, true));
        assertFalse(ClaimPermissions.mayBeTrusted(FactionIds.ANARCHISM, true));
    }

    @Test
    void havingChosenAnyOtherPartyIsUnrestricted() {
        for (String party : new String[]{FactionIds.COMMUNISM, FactionIds.CAPITALISM, FactionIds.MONARCHY}) {
            assertTrue(ClaimPermissions.mayClaim(party, true), party);
            assertTrue(ClaimPermissions.mayReceiveTransfer(party, true), party);
            assertTrue(ClaimPermissions.mayBeTrusted(party, true), party);
        }
    }

    @Test
    void havingChosenNothingStillOverridesAnUnknownPartyId() {
        // A hand-edited save naming a party this build has never heard of must not lock anyone out, whether
        // or not a choice was recorded.
        assertTrue(ClaimPermissions.mayClaim("somethingelse", false));
        assertTrue(ClaimPermissions.mayReceiveTransfer("somethingelse", false));
        assertTrue(ClaimPermissions.mayBeTrusted("somethingelse", false));
    }

    // --- releasing land a player already holds ---

    @Test
    void joiningAnarchismReleasesTheLandTheyAlreadyHold() {
        assertTrue(ClaimPermissions.mustReleaseLand(FactionIds.ANARCHISM, true));
    }

    @Test
    void aPlayerWhoHasNotChosenKeepsTheirLand() {
        // The half that is easy to get wrong. The effective id is Anarchism for an undecided player, so
        // releasing on the id alone would strip the land of every player who has never opened the menu —
        // and hand them a refund they were never charged, since a claim they cannot make may still be one
        // they were given, carved, or placed by an admin.
        assertFalse(ClaimPermissions.mustReleaseLand(FactionIds.ANARCHISM, false));
    }

    @Test
    void everyOtherPartyKeepsItsLand() {
        for (String party : new String[]{FactionIds.COMMUNISM, FactionIds.CAPITALISM, FactionIds.MONARCHY}) {
            assertFalse(ClaimPermissions.mustReleaseLand(party, true), party);
        }
        // No party system, and an id this build does not know: nothing to enforce, nothing to take.
        assertFalse(ClaimPermissions.mustReleaseLand(null, true));
        assertFalse(ClaimPermissions.mustReleaseLand(null, false));
        assertFalse(ClaimPermissions.mustReleaseLand("somethingelse", true));
    }

    @Test
    void releasingIsNotTheNegationOfClaiming() {
        // Documented as a trap: these two answer different questions. A player who chose nothing may claim
        // new land AND keep what they have, so neither can be derived from the other.
        assertTrue(ClaimPermissions.mayClaim(FactionIds.ANARCHISM, false));
        assertFalse(ClaimPermissions.mustReleaseLand(FactionIds.ANARCHISM, false));
    }
}
