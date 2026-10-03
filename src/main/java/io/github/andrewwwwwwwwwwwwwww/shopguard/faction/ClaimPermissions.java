package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

/**
 * What each party may do with claims — Anarchism's {@code Vô chính phủ} (spec 37), as pure functions of a
 * party id.
 *
 * <p>Kept separate from {@link PlayerFactions} on purpose. The rules are the part worth testing, and testing
 * them through the bridge would need a live server, a Fabric loader and an EconomyCraft install. As plain
 * functions of a {@code String} they are exhaustively unit-testable, and the only thing the call sites have
 * to get right is passing the id in.
 *
 * <p><strong>{@code null} means "no faction system".</strong> Every method answers permissively for it,
 * because the only correct behaviour without EconomyCraft installed is to leave claims working exactly as
 * they did before. That is also the answer for a player who has chosen no party, which is deliberate: with
 * no economy there is no party, and with no choice there is no party to restrict them.
 *
 * <p><strong>Unknown ids are permissive too</strong>, and that is a policy choice rather than an oversight.
 * A typo or a party added by a newer EconomyCraft must not lock every player out of claiming; the cost of
 * being wrong is one player getting a faction they should not have, and the next EconomyCraft release that
 * knows the id closes it.
 */
public final class ClaimPermissions {
    private ClaimPermissions() {}

    /**
     * Whether a player of this party may create a claim.
     *
     * <p>Anarchism may not: {@code Vô chính phủ} is "no government", and the spec's Anarchism is the party
     * that refuses to recognise land ownership at all. It is the one rule in this class that blocks an
     * action rather than a transaction, and it is enforced before any money moves, so a refusal is free.
     */
    public static boolean mayClaim(String factionId) {
        return !isAnarchist(factionId);
    }

    /**
     * Whether a claim may be transferred <em>to</em> a player of this party.
     *
     * <p>Blocked for Anarchism for the same reason as claiming: accepting a claim is accepting government.
     * Note this is about the <em>recipient</em>. The sender's party is irrelevant — an Anarchist holding a
     * claim they were given before choosing, or before this rule existed, may still give it away.
     */
    public static boolean mayReceiveTransfer(String factionId) {
        return !isAnarchist(factionId);
    }

    /**
     * Whether a player of this party may be added to a claim's trust list.
     *
     * <p>Blocked for Anarchism, and this is the strictest of the three: trust is how a player gets to
     * <em>build inside</em> someone else's claim, which is precisely the arrangement {@code Vô chính phủ}
     * refuses to participate in. The rule is deliberately one-directional — the claim's owner is free to
     * trust whoever they like, and an Anarchist may still be trusted by an owner who removes them again.
     */
    public static boolean mayBeTrusted(String factionId) {
        return !isAnarchist(factionId);
    }

    private static boolean isAnarchist(String factionId) {
        return FactionIds.ANARCHISM.equals(factionId);
    }
}
