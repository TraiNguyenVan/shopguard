package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

import com.reazip.economycraft.api.v1.EconomyCraftApi;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * {@link PlayerFactions.Backend} over the real EconomyCraft API.
 *
 * <p>Like {@link io.github.andrewwwwwwwwwwwwwww.shopguard.economy.EconomyCraftBackend}, this is loaded only
 * after {@code FabricLoader} has confirmed EconomyCraft is present, so on a server without it these
 * references never have to resolve.
 *
 * <p>Every method must be called from the server thread — the API enforces this and throws otherwise. All
 * call sites are inside block interaction callbacks or command execution, which already run there.
 */
final class EconomyCraftFactionsBackend implements PlayerFactions.Backend {
    private final MinecraftServer server;

    EconomyCraftFactionsBackend() {
        this.server = ShopGuard.server;
        if (server == null) {
            throw new IllegalStateException("EconomyCraft faction backend created before the server started");
        }
    }

    private EconomyCraftApi api() {
        return EconomyCraftApi.get(server);
    }

    @Override
    public String factionId(UUID playerId) {
        return api().factions().factionId(playerId);
    }

    @Override
    public String factionDisplayName(UUID playerId) {
        return api().factions().factionDisplayName(playerId);
    }

    @Override
    public boolean hasChosen(UUID playerId) {
        return api().factions().hasChosen(playerId);
    }

    @Override
    public double claimCostMultiplier(UUID playerId) {
        return api().factions().claimCostMultiplier(playerId);
    }
}
