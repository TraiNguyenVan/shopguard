package io.github.andrewwwwwwwwwwwwwww.shopguard;

import io.github.andrewwwwwwwwwwwwwww.shopguard.claim.Claim;
import io.github.andrewwwwwwwwwwwwwww.shopguard.claim.ClaimShape;
import io.github.andrewwwwwwwwwwwwwww.shopguard.economy.ClaimPricing;
import io.github.andrewwwwwwwwwwwwwww.shopguard.faction.PlayerFactions;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The golden-shovel claim tool.
 * <ul>
 *   <li>Right-click two corners → apply the current mode to that rectangle.</li>
 *   <li>Right-click the air → toggle between CLAIM (add area) and CARVE (remove area).</li>
 * </ul>
 * Adding a rectangle that touches any of your own claims merges them all into one claim (so touching
 * claims are truly one). The shovel does NOT hijack left-click, so you can still break blocks with it.
 */
public final class ClaimTool {
    private ClaimTool() {}

    private static final Map<UUID, Boolean> CARVE_MODE = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();

    /** How far the tool can target a corner beyond vanilla reach (aim at the ground and click). */
    public static final double CORNER_RANGE = 64.0;

    /**
     * One physical right-click can reach us twice: vanilla sends a use-on-block packet AND, when the
     * shovel has no vanilla use on that block, a follow-up use-item packet (which our raycast would
     * treat as a second corner — instantly making a 1-block claim). Process at most one action per
     * short window.
     */
    static boolean debounce(ServerPlayer sp, Map<UUID, Long> lastAction) {
        long now = sp.level().getGameTime();
        Long last = lastAction.put(sp.getUUID(), now);
        return last != null && now - last < 2;
    }

    public static void register() {
        // Right-click a block: set a corner (applies the current mode on the second corner).
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (player.getItemInHand(hand).getItem() != Items.GOLDEN_SHOVEL) return InteractionResult.PASS;
            if (!debounce(sp, LAST_ACTION)) handleCorner(sp, hit.getBlockPos());
            return InteractionResult.SUCCESS; // consume — also cancels vanilla path-making
        });
        // Right-click without a block in reach: vanilla treats any aim past ~4.5 blocks as an "air
        // click". Raycast the player's view — a block in sight (up to CORNER_RANGE) sets that corner;
        // only a true sky-aim toggles CLAIM <-> CARVE mode.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (player.getItemInHand(hand).getItem() != Items.GOLDEN_SHOVEL) return InteractionResult.PASS;
            if (!debounce(sp, LAST_ACTION)) {
                HitResult hit = sp.pick(CORNER_RANGE, 1.0f, false);
                if (hit.getType() == HitResult.Type.BLOCK) {
                    handleCorner(sp, ((BlockHitResult) hit).getBlockPos());
                } else {
                    toggleMode(sp);
                }
            }
            return InteractionResult.SUCCESS;
        });
    }

    /** Forget a player's pending corner and mode (called on disconnect). */
    public static void clearPlayer(UUID uid) {
        CARVE_MODE.remove(uid);
        PENDING.remove(uid);
        LAST_ACTION.remove(uid);
    }

    private static void toggleMode(ServerPlayer sp) {
        UUID uid = sp.getUUID();
        boolean carve = !CARVE_MODE.getOrDefault(uid, false);
        CARVE_MODE.put(uid, carve);
        PENDING.remove(uid);
        // Overlay (action bar above the hotbar) — fades on its own instead of filling chat.
        sp.sendOverlayMessage(Component.literal("Shovel mode: " + (carve ? "CARVE (remove area)" : "CLAIM (add area)"))
                .withStyle(carve ? ChatFormatting.GOLD : ChatFormatting.GREEN));
    }

    private static void handleCorner(ServerPlayer sp, BlockPos pos) {
        UUID uid = sp.getUUID();
        boolean carve = CARVE_MODE.getOrDefault(uid, false);
        BlockPos first = PENDING.remove(uid);
        if (first == null) {
            PENDING.put(uid, pos.immutable());
            sp.sendOverlayMessage(Component.literal(
                    (carve ? "Carve" : "Claim") + ": first corner set — right-click the opposite corner.")
                    .withStyle(ChatFormatting.YELLOW));
            if (!carve) quoteRate(sp);
            return;
        }
        if (carve) carve(sp, first, pos); else add(sp, first, pos);
    }

    /**
     * State the current price scale when the first corner is set.
     *
     * <p>The total isn't knowable yet — the rectangle isn't selected — so this quotes the rate instead,
     * which is what lets a player work out the cost of the area they have in mind before committing to
     * the second click. The amount actually charged is reported on completion.
     */
    private static void quoteRate(ServerPlayer sp) {
        if (!ClaimPricing.charging()) return;
        if (ShopGuard.CONFIG.claimCostFreeForOps && ProtectionHandler.isOp(sp)) return;
        // The player's own rate, so a Monarchy owner sees the halved price the charge will actually take.
        sp.sendOverlayMessage(Component.literal("Costs " + ClaimPricing.money(ClaimPricing.quote(sp, 1))
                        + " per block right now (inflation factor " + ClaimPricing.trim(ClaimPricing.factor()) + "x"
                        + (ClaimPricing.discounted(sp)
                        ? ", your party discount included" : "") + ")")
                .withStyle(ClaimPricing.discounted(sp) ? ChatFormatting.GREEN : ChatFormatting.AQUA));
    }

    private static String dim(ServerPlayer sp) {
        return sp.level().dimension().identifier().toString();
    }

    private static void add(ServerPlayer sp, BlockPos a, BlockPos b) {
        String dim = dim(sp);
        UUID uid = sp.getUUID();
        boolean op = ProtectionHandler.isOp(sp);

        ClaimShape merged = new ClaimShape();
        merged.addRect(a.getX(), a.getZ(), b.getX(), b.getZ());

        // Flood-merge with all of the player's claims (in this dimension) that touch the result.
        List<Claim> absorb = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Claim c : ShopGuard.STORE.all()) {
                if (!c.owner.equals(uid) || !c.dimension.equals(dim) || absorb.contains(c)) continue;
                if (merged.touches(c.shape)) {
                    merged.union(c.shape);
                    absorb.add(c);
                    changed = true;
                }
            }
        }

        Set<Long> absorbedIds = new HashSet<>();
        int absorbedCells = 0;
        for (Claim c : absorb) { absorbedIds.add(c.id); absorbedCells += c.shape.count(); }

        if (ShopGuard.STORE.overlapsOther(dim, merged, absorbedIds)) {
            error(sp, "That overlaps another player's claim.");
            return;
        }
        if (!ShopGuard.STORE.allowedByZones(dim, merged)) {
            error(sp, "Claims can only be made inside a claim zone here.");
            return;
        }
        if (!op && merged.count() > ShopGuard.CONFIG.maxClaimArea) {
            error(sp, "Too big — max " + ShopGuard.CONFIG.maxClaimArea + " blocks per claim.");
            return;
        }
        int ownerTotal = ShopGuard.STORE.totalCellsOfOwner(uid) - absorbedCells + merged.count();
        if (!op && ownerTotal > ShopGuard.CONFIG.maxTotalPerPlayer) {
            error(sp, "That would exceed your total claim limit (" + ShopGuard.CONFIG.maxTotalPerPlayer + " blocks).");
            return;
        }

        // Anarchism's Vô chính phủ: no government, no claims. Checked here — after the overlap, zone and
        // area checks and before the charge — so a refusal costs nothing and mutates nothing.
        if (!PlayerFactions.mayClaim(uid)) {
            error(sp, PlayerFactions.describe(uid) + " refuses to recognise land ownership, so it cannot claim.");
            return;
        }

        // Charge the net-new columns only, and only once every check above has passed.
        //
        // This deliberately happens BEFORE any mutation of the store. The merge below deletes the absorbed
        // claims, so charging afterwards and bailing on failure would leave the merge half-applied — the
        // surviving claim would still hold its old shape while its siblings had already been dropped from
        // the map, and the next save would persist that loss.
        int netNew = merged.count() - absorbedCells;
        ClaimPricing.Result charge = ClaimPricing.charge(sp, netNew);
        if (!charge.ok()) {
            error(sp, charge.reason());
            return;
        }

        // The absorbed claims' money carries into the survivor. Their area is now part of this claim, so
        // without summing their `paid` a merge would quietly destroy the refundable value of ground the
        // player had already paid for.
        long mergedPaid = 0L;
        for (Claim c : absorb) mergedPaid = saturatedAdd(mergedPaid, c.paid);

        boolean mergedExisting = !absorb.isEmpty();
        Claim result;
        if (mergedExisting) {
            result = absorb.get(0); // keep the first claim's id, absorb the rest
            for (int i = 1; i < absorb.size(); i++) ShopGuard.STORE.removeNoSave(absorb.get(i).id);
        } else {
            result = ShopGuard.STORE.newClaim(uid, sp.getName().getString(), dim);
        }
        result.paid = saturatedAdd(mergedPaid, charge.amount());
        result.refunded = 0L; // one combined claim, one clean refund ledger
        result.shape = merged;
        ShopGuard.STORE.save();
        ClaimVisualizer.refresh(sp.level());
        ok(sp, (mergedExisting ? "Claim updated" : "Claim created") + " — " + result.shape.count() + " blocks."
                + (charge.amount() > 0 ? " Cost " + ClaimPricing.money(charge.amount())
                        + " for " + netNew + " new blocks." : ""));
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static void carve(ServerPlayer sp, BlockPos a, BlockPos b) {
        String dim = dim(sp);
        UUID uid = sp.getUUID();
        boolean op = ProtectionHandler.isOp(sp);
        Claim target = ShopGuard.STORE.claimAt(dim, a.getX(), a.getZ());
        if (target == null || (!target.owner.equals(uid) && !op)) {
            error(sp, "Carve from inside your own claim (first corner must be claimed land).");
            return;
        }

        ClaimShape original = target.shape.copy();
        long paidBefore = target.paid;
        int before = target.shape.count();

        target.shape.removeRect(a.getX(), a.getZ(), b.getX(), b.getZ());
        int after = target.shape.count();

        // Carving pays no cash by default, but the claim's recorded `paid` drops in proportion to the area
        // removed. Without that, a player could claim a large area, carve it down to a single column for
        // free, and still release the claim for the original full amount.
        long cash = ClaimPricing.carve(target, before, after)[0];

        if (target.shape.isEmpty()) {
            // Carved to nothing: the owner is giving the land up, so this is a release, not a reshape.
            // An op doing it is a grief response, not a sale, so it never pays the owner out.
            boolean ownerRelease = target.owner.equals(uid);
            ClaimPricing.Result refund = ownerRelease
                    ? ClaimPricing.refund(sp, target)
                    : ClaimPricing.Result.free();
            if (!refund.ok()) {
                // Nothing has been credited and nothing has been deleted, so undo the carve wholesale and
                // leave the player with both their land and their money.
                target.shape = original;
                target.paid = paidBefore;
                error(sp, refund.reason());
                return;
            }
            ShopGuard.STORE.remove(target.id);
            // Pay the carve's own refund, if the rate asked for one. `paid` was already reduced to zero by
            // the carve, so the release refund above is necessarily 0 on this path — these are not two
            // payments for the same money, the carve consumed the ledger and this pays for it.
            long carveCash = payCarveRefund(sp, target, cash);
            ok(sp, "Claim released."
                    + (refund.amount() > 0 ? " Refunded " + ClaimPricing.money(refund.amount()) + "." : "")
                    + (carveCash > 0 ? " Refunded " + ClaimPricing.money(carveCash) + " for the carved area."
                            : ""));
        } else {
            long carveCash = payCarveRefund(sp, target, cash);
            ShopGuard.STORE.save();
            ok(sp, "Carved — " + after + " blocks remain."
                    + (carveCash > 0 ? " Refunded " + ClaimPricing.money(carveCash) + "." : ""));
        }
        ClaimVisualizer.refresh(sp.level());
    }

    /**
     * Pay what a kept carve earned, and report what was actually paid.
     *
     * <p>The returned amount is what the player received, which is not necessarily {@code owed}: at the
     * default rate it is always 0, and if the economy is unavailable there is nobody to pay. Returning the
     * real figure keeps the confirmation message honest instead of announcing a refund that never landed.
     */
    private static long payCarveRefund(ServerPlayer sp, Claim claim, long owed) {
        if (owed <= 0) return 0L;
        if (!ClaimPricing.creditCarveRefund(sp, claim, owed)) {
            ShopGuard.LOGGER.warn("Carve refund of {} for {} could not be credited; the claim's recorded "
                    + "cost was still reduced.", owed, claim.id);
            return 0L;
        }
        return owed;
    }

    private static void error(ServerPlayer sp, String msg) {
        sp.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.RED));
    }
    private static void ok(ServerPlayer sp, String msg) {
        sp.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GREEN));
    }
}
