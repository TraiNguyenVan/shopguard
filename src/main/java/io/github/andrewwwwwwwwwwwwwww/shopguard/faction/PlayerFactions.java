package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import net.fabricmc.loader.api.FabricLoader;

import java.util.UUID;

/**
 * The player's party, as far as claim rules are concerned.
 *
 * <p>The mirror image of {@link io.github.andrewwwwwwwwwwwwwww.shopguard.economy.ClaimEconomy}: EconomyCraft is
 * a soft dependency here too, in the other direction. Two of the faction effects that touch claims —
 * Monarchy's halved cost and Anarchism's {@code Vô chính phủ} — have to be enforced <em>here</em>,
 * because this is where claiming, transferring and trusting happen. So ShopGuard needs to read the party,
 * and it needs to do so without breaking on a server that has no EconomyCraft.
 *
 * <p>{@link #backend()} returns {@code null} in that case, and every caller treats a missing party as "no
 * faction rules apply", which is the same degradation {@code ClaimEconomy} uses for a missing economy.
 * The class that actually touches the EconomyCraft API lives in {@link EconomyCraftFactionsBackend} and is
 * only loaded once {@code FabricLoader} confirms the mod is present, so nothing here triggers a class load
 * that would fail without it.
 *
 * <p><strong>The rules themselves are not here.</strong> {@link ClaimPermissions} holds them as pure
 * functions of a party id, so what Anarchism may and may not do is unit-testable without a server; this
 * class only supplies the id.
 */
public final class PlayerFactions {
    private PlayerFactions() {}

    /** The party lookup claim rules need, or {@code null} if EconomyCraft isn't installed. */
    public interface Backend {
        /** The player's effective party, one of the EconomyCraft {@code FactionIds} constants. */
        String factionId(UUID playerId);

        /** The party's English display name, for telling a player which party refused them. */
        String factionDisplayName(UUID playerId);

        /** The multiplier to apply to this player's claim cost. {@code 1.0} means unchanged. */
        double claimCostMultiplier(UUID playerId);
    }

    private static volatile Backend cached;
    private static boolean warnedMissing;

    /** The faction backend, or {@code null} when EconomyCraft isn't installed. */
    public static Backend backend() {
        Backend current = cached;
        if (current != null) return current;
        if (!FabricLoader.getInstance().isModLoaded("economycraft")) {
            if (!warnedMissing) {
                warnedMissing = true;
                ShopGuard.LOGGER.warn(
                        "EconomyCraft is not installed — faction claim rules are disabled: no party discount, "
                                + "and Anarchism's Vô chính phủ restrictions do not apply. "
                                + "Install economycraft for those rules.");
            }
            return null;
        }
        if (ShopGuard.server == null) {
            // The backend captures the server on construction, so it cannot be built during mod init.
            return null;
        }
        synchronized (PlayerFactions.class) {
            if (cached == null) cached = new EconomyCraftFactionsBackend();
            return cached;
        }
    }

    /**
     * The player's party id, or {@code null} when there is no backend.
     *
     * <p>Returning {@code null} rather than a default id is deliberate: "no economy" and "a player who
     * chose nothing" are different situations, and {@code ClaimPermissions} gives them the same answer
     * on purpose (neither party restricts claims) without pretending they are the same fact.
     */
    public static String factionId(UUID playerId) {
        Backend backend = backend();
        return backend == null ? null : backend.factionId(playerId);
    }

    /** Whether this player may claim land, given their party. */
    public static boolean mayClaim(UUID playerId) {
        return ClaimPermissions.mayClaim(factionId(playerId));
    }

    /** Whether a claim may be transferred <em>to</em> this player. */
    public static boolean mayReceiveTransfer(UUID playerId) {
        return ClaimPermissions.mayReceiveTransfer(factionId(playerId));
    }

    /** Whether this player may be added to another player's trust list. */
    public static boolean mayBeTrusted(UUID playerId) {
        return ClaimPermissions.mayBeTrusted(factionId(playerId));
    }

    /** The claim-cost multiplier for this player; {@code 1.0} when there is no backend. */
    public static double claimCostMultiplier(UUID playerId) {
        Backend backend = backend();
        return backend == null ? 1.0 : backend.claimCostMultiplier(playerId);
    }

    /** The player's party display name for a refusal message, or a neutral phrase when unknown. */
    public static String describe(UUID playerId) {
        Backend backend = backend();
        return backend == null ? "Your party" : "Your party (" + backend.factionDisplayName(playerId) + ")";
    }
}
