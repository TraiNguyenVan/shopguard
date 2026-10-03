package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

/**
 * The party ids ShopGuard compares against, copied from EconomyCraft's API.
 *
 * <p>These are strings because EconomyCraft's {@code FactionId} enum lives in its {@code common} module,
 * which is not on ShopGuard's compile classpath — only the API jar is. They are <em>copied</em> rather than
 * imported for a second reason: {@link ClaimPermissions} must stay pure so it can be unit-tested without a
 * server or an EconomyCraft install, and importing the API into it would break both.
 *
 * <p>The duplication is the risk this file exists to contain. {@code FactionIdContractTest} asserts every
 * constant here equals its EconomyCraft counterpart, so a party renamed on either side fails the build
 * instead of quietly disabling the rule that depends on it.
 */
public final class FactionIds {
    public static final String COMMUNISM = "communism";
    public static final String CAPITALISM = "capitalism";
    public static final String MONARCHY = "monarchy";
    public static final String ANARCHISM = "anarchism";

    private FactionIds() {}
}
