package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The copied party ids have to stay copied correctly.
 *
 * <p>{@link FactionIds} duplicates EconomyCraft's API constants as plain strings, because
 * {@link ClaimPermissions} must stay testable without an EconomyCraft install. That duplication is the one
 * place in this integration where a silent failure is possible: rename a party in EconomyCraft, leave the
 * copy alone, and {@code Vô chính phủ} quietly stops applying to anyone — no error, no crash, just a rule
 * that stopped existing.
 *
 * <p>So the copy is asserted against the real API here. This test is the reason the EconomyCraft jar is on
 * the test classpath, and it fails the build rather than the runtime.
 */
class FactionIdContractTest {

    @Test
    void everyPartyIdMatchesEconomycraft() {
        assertEquals(com.reazip.economycraft.api.v1.FactionIds.COMMUNISM, FactionIds.COMMUNISM);
        assertEquals(com.reazip.economycraft.api.v1.FactionIds.CAPITALISM, FactionIds.CAPITALISM);
        assertEquals(com.reazip.economycraft.api.v1.FactionIds.MONARCHY, FactionIds.MONARCHY);
        assertEquals(com.reazip.economycraft.api.v1.FactionIds.ANARCHISM, FactionIds.ANARCHISM);
    }

    @Test
    void economycraftHasNotAddedAPartyThisCopyHasNotReviewed() {
        // The failure mode this whole file exists to catch, checked from the other side: a new party in
        // EconomyCraft means the copy needs a deliberate decision, not a silent miss. Note this reads the
        // API's constants by reflection — the enum itself lives in EconomyCraft's common module, which is
        // not on this classpath, and the constant list is exactly the part ShopGuard copied.
        Set<String> upstream = Arrays.stream(com.reazip.economycraft.api.v1.FactionIds.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(FactionIdContractTest::read)
                .collect(Collectors.toSet());
        Set<String> copied = Set.of(FactionIds.COMMUNISM, FactionIds.CAPITALISM, FactionIds.MONARCHY,
                FactionIds.ANARCHISM);
        assertEquals(Set.copyOf(upstream), copied,
                "EconomyCraft's party ids changed — re-check which of them need a claim rule, then update "
                        + FactionIds.class.getName());
    }

    @Test
    void aClaimCostMultiplierIsPartOfTheApiWeDependOn() {
        // ShopGuard calls this on every quote and charge. A rename or signature change here breaks the
        // integration at runtime, where no unit test would see it, so it is pinned by name instead.
        assertEquals(1, Arrays.stream(com.reazip.economycraft.api.v1.FactionApi.class.getMethods())
                .filter(m -> m.getName().equals("claimCostMultiplier"))
                .filter(m -> m.getParameterCount() == 1)
                .filter(m -> m.getReturnType() == double.class)
                .count(), "FactionApi.claimCostMultiplier(UUID) -> double");
    }

    private static String read(Field f) {
        try {
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new AssertionError("could not read " + f, e);
        }
    }
}
