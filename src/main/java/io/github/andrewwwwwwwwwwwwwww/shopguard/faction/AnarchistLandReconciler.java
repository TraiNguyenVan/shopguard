package io.github.andrewwwwwwwwwwwwwww.shopguard.faction;

import io.github.andrewwwwwwwwwwwwwww.shopguard.ClaimVisualizer;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import io.github.andrewwwwwwwwwwwwwww.shopguard.claim.Claim;
import io.github.andrewwwwwwwwwwwwwww.shopguard.economy.ClaimPricing;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Releases the land of players who have joined Anarchism, and pays them for it.
 *
 * <p>{@link ClaimPermissions#mayClaim} already stops an Anarchist claiming, but it only ever governs the
 * <em>next</em> claim. A player who claimed land first and chose Anarchism afterwards kept it, which is the one
 * thing that makes the party meaningless: an Anarchist owning protected ground is a landowner by every measure
 * the server can enforce. So joining Anarchism has to take the land with it, and — because the land was bought —
 * pay for it back through the normal release path.
 *
 * <p><strong>Why this polls instead of listening for the choice.</strong> The choice is made in EconomyCraft, in
 * a {@code FactionStore#select} that is deliberately write-only over a read-only API: there is no event to
 * subscribe to, and adding one would mean a second mod writing faction state it does not own. The question the
 * reconciler asks is therefore idempotent and cheap — "does this player's party disagree with the land they
 * hold?" — so it is answered again on a slow interval and on login instead of once. That has a second benefit:
 * a player who joined Anarchism while the server was down, or under an older build with no rule at all, is
 * brought into line on their next login rather than needing an admin command.
 *
 * <p><strong>The money path is ShopGuard's own, unchanged.</strong> Release goes through
 * {@link ClaimPricing#refund}, exactly as {@code /claim remove} does, so the payout is the claim's recorded cost
 * less {@code claimRefundFeeRate} — 75% back at the live setting — against the same daily allowance. The refund
 * is <em>all or nothing</em>: if it does not fit today's allowance the release is refused and the player keeps
 * both the land and the money, and the next sweep tries again tomorrow. Destroying the land anyway would take
 * money a player paid for and give nothing back, and paying the part that fits is not possible in a money model
 * with no negative balances and no escrow.
 */
public final class AnarchistLandReconciler {

    /** Ticks between sweeps. A minute is frequent enough to feel immediate and rare enough to be free. */
    private static final int SWEEP_INTERVAL_TICKS = 20 * 60;

    /** Ticks to wait before warning the same player again about a refund their allowance will not cover. */
    private static final long WARN_COOLDOWN_MILLIS = 10 * 60 * 1000L;

    private static int ticks;
    private static final Map<UUID, Long> LAST_WARNED = new HashMap<>();

    private AnarchistLandReconciler() {}

    /** Hooks the login check and the slow sweep. Called from {@code ShopGuard#onInitialize}. */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> reconcile(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks < SWEEP_INTERVAL_TICKS) return;
            ticks = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) reconcile(player);
        });
    }

    /**
     * Releases every claim this player holds if their party forbids holding land, and tells them what happened.
     *
     * <p>Public because it is the unit the join path and the sweep path both call, and because it is the only
     * place the decision, the payout and the removal have to agree.
     */
    public static void reconcile(ServerPlayer player) {
        if (player == null || ShopGuard.server == null) return;
        if (!PlayerFactions.mustReleaseLand(player.getUUID())) return;

        // A copy: the release removes from the store, and byOwner's list is a view of it.
        List<Claim> owned = List.copyOf(ShopGuard.STORE.byOwner(player.getUUID()));
        if (owned.isEmpty()) return;

        long released = 0L;
        int blocks = 0;
        int kept = 0;
        String refusal = null;
        for (Claim claim : owned) {
            ClaimPricing.Result refund = ClaimPricing.refund(player, claim);
            if (!refund.ok()) {
                kept++;
                refusal = refund.reason();
                continue;
            }
            ShopGuard.STORE.remove(claim.id);
            released += refund.amount();
            blocks += claim.shape.count();
        }

        if (kept > 0) {
            warn(player, refusal);
        } else {
            LAST_WARNED.remove(player.getUUID());
        }
        if (released > 0L || kept == 0) {
            ClaimVisualizer.refresh(player.level());
        }
        ShopGuard.LOGGER.info("Anarchism: released {} claim(s) / {} blocks for {} (refunded {}, {} kept)",
                owned.size() - kept, blocks, player.getName().getString(), released, kept);
        notify(player, owned.size() - kept, blocks, released, kept, refusal);
    }

    /** The release as the player experiences it: what went, what it paid, and what is still standing. */
    private static void notify(ServerPlayer player, int released, int blocks, long money, int kept, String refusal) {
        if (released > 0) {
            player.sendSystemMessage(Component.literal(
                    "Anarchism recognises no land ownership, so your " + released + " claim(s) covering "
                            + blocks + " blocks were released"
                            + (money > 0L ? " and " + ClaimPricing.money(money) + " was refunded to you"
                                    : " (they were not paid for, so nothing was owed)")
                            + ".").withStyle(ChatFormatting.YELLOW));
        }
        if (kept > 0) {
            player.sendSystemMessage(Component.literal(refusal == null ? "Some claims were kept."
                    : refusal).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Says why the land is still theirs, at most every ten minutes.
     *
     * <p>The sweep runs every minute and this condition — a claim worth more than the day's remaining refund
     * allowance — can persist for a whole day, so an unthrottled warning would put the same line in the player's
     * chat once a minute.
     */
    private static void warn(ServerPlayer player, String refusal) {
        long now = System.currentTimeMillis();
        Long last = LAST_WARNED.get(player.getUUID());
        if (last != null && now - last < WARN_COOLDOWN_MILLIS) return;
        LAST_WARNED.put(player.getUUID(), now);
        ShopGuard.LOGGER.warn("Anarchism: {} keeps land they cannot keep: {}",
                player.getName().getString(), refusal);
    }
}