package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import net.fabricmc.loader.api.FabricLoader;

import java.util.UUID;

/**
 * The EconomyCraft side of claim pricing, behind an interface so that ShopGuard neither compiles its
 * call sites against EconomyCraft nor breaks when it is absent.
 *
 * <p>EconomyCraft is a <em>soft</em> dependency. {@link #backend()} returns {@code null} when the mod
 * isn't installed, and every caller treats that as "claim pricing is unavailable" rather than as an
 * error: ShopGuard has to keep working as a pure land-claim mod with no economy on the server. The
 * class that actually touches the EconomyCraft API lives in {@link EconomyCraftBackend} and is only
 * loaded once {@code FabricLoader} confirms the mod is present, so nothing here triggers a class load
 * that would fail on a server without it.
 */
public final class ClaimEconomy {
    private ClaimEconomy() {}

    /** The operations claim pricing needs from an economy, or {@code null} if there isn't one. */
    public interface Backend {
        boolean available();

        /** The economy's live inflation multiplier — the same signal that scales item buy prices. */
        double inflationMultiplier();

        long balance(UUID player);

        /** Burn {@code amount} from the player. All-or-nothing: false leaves the balance untouched. */
        boolean charge(UUID player, long amount);

        /** Create {@code amount} in the player's balance. */
        boolean credit(UUID player, long amount);

        String formatMoney(long amount);
    }

    private static volatile Backend cached;
    private static boolean warnedMissing;

    /** The economy backend, or {@code null} when EconomyCraft isn't installed. */
    public static Backend backend() {
        Backend current = cached;
        if (current != null) return current;
        if (!FabricLoader.getInstance().isModLoaded("economycraft")) {
            if (!warnedMissing) {
                warnedMissing = true;
                ShopGuard.LOGGER.warn(
                        "EconomyCraft is not installed — claim pricing is disabled and claims are free. "
                                + "Install economycraft to charge for claim area.");
            }
            return null;
        }
        if (ShopGuard.server == null) {
            // The backend captures the server on construction, so it cannot be built during mod init.
            return null;
        }
        synchronized (ClaimEconomy.class) {
            if (cached == null) cached = new EconomyCraftBackend();
            return cached;
        }
    }
}
