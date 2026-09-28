package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.EconomyCraftApi;
import com.reazip.economycraft.api.v1.MutationSource;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * {@link ClaimEconomy.Backend} over the real EconomyCraft API.
 *
 * <p>This is the only ShopGuard class that references EconomyCraft. It is instantiated exclusively from
 * {@link ClaimEconomy#backend()} after {@code FabricLoader} has confirmed the mod is present, so on a
 * server without EconomyCraft this class is never loaded and the missing types never have to resolve.
 *
 * <p>Every method must be called from the server thread — the API enforces this and throws otherwise.
 * All ShopGuard call sites are inside block/item interaction callbacks or command execution, which
 * already run there.
 */
final class EconomyCraftBackend implements ClaimEconomy.Backend {
    /** Namespaced so claim money movement is attributable in {@code /transactions}. */
    private static final MutationSource CLAIM_COST = MutationSource.of("shopguard:claimcost");
    private static final MutationSource CLAIM_REFUND = MutationSource.of("shopguard:claimrefund");

    private final MinecraftServer server;

    EconomyCraftBackend() {
        this.server = ShopGuard.server;
        if (server == null) {
            throw new IllegalStateException("EconomyCraft backend created before the server started");
        }
    }

    private EconomyCraftApi api() {
        return EconomyCraftApi.get(server);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public double inflationMultiplier() {
        return api().inflationMultiplier();
    }

    @Override
    public long balance(UUID player) {
        return api().balances().getBalance(player);
    }

    @Override
    public boolean charge(UUID player, long amount) {
        if (amount <= 0) return true;
        BalanceMutationResult result = api().balances().removeMoney(player, amount, CLAIM_COST);
        return result.successful();
    }

    @Override
    public boolean credit(UUID player, long amount) {
        if (amount <= 0) return true;
        BalanceMutationResult result = api().balances().addMoney(player, amount, CLAIM_REFUND);
        return result.successful();
    }

    @Override
    public String formatMoney(long amount) {
        return api().formatMoney(amount);
    }
}
