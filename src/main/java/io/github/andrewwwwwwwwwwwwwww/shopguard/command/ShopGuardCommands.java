package io.github.andrewwwwwwwwwwwwwww.shopguard.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ClaimVisualizer;
import io.github.andrewwwwwwwwwwwwwww.shopguard.Config;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ProtectionHandler;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import io.github.andrewwwwwwwwwwwwwww.shopguard.claim.AdminZone;
import io.github.andrewwwwwwwwwwwwwww.shopguard.claim.Claim;
import io.github.andrewwwwwwwwwwwwwww.shopguard.economy.ClaimPricing;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code /claim} command tree, kept deliberately small:
 * <ul>
 *   <li>{@code /claim} — everything about your situation: the claim you're standing in, your claims,
 *       and your used/max totals.</li>
 *   <li>{@code /claim show} — toggle border visibility (zones dark red, your claims green, others orange).</li>
 *   <li>{@code /claim remove} — remove the claim you're standing in (owner; ops may remove anyone's).</li>
 *   <li>{@code /claim trust <player>} — toggle a player's build access on the claim you're standing in.</li>
 *   <li>{@code /claim transfer <player> [confirm]} — hand the claim you're standing in to another player
 *       (owner; ops may transfer anyone's). A clean handover unless {@code keepOldOwnerTrusted} is set.</li>
 *   <li>{@code /claim zone add|list|remove} — ops: manage the zones players may claim in.</li>
 * </ul>
 */
public final class ShopGuardCommands {
    private ShopGuardCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var zone = Commands.literal("zone")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("add")
                        .then(Commands.argument("corner1", BlockPosArgument.blockPos())
                                .then(Commands.argument("corner2", BlockPosArgument.blockPos())
                                        .executes(ctx -> zoneAdd(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "corner1"),
                                                BlockPosArgument.getBlockPos(ctx, "corner2"))))))
                .then(Commands.literal("list").executes(ctx -> zoneList(ctx.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .suggests((c, bld) -> SharedSuggestionProvider.suggest(
                                        ShopGuard.STORE.zones().stream().map(z -> String.valueOf(z.id)), bld))
                                .executes(ctx -> zoneRemove(ctx.getSource(), LongArgumentType.getLong(ctx, "id")))));

        dispatcher.register(Commands.literal("claim")
                .executes(ctx -> info(ctx.getSource()))
                .then(Commands.literal("show").executes(ctx -> show(ctx.getSource())))
                .then(Commands.literal("remove").executes(ctx -> remove(ctx.getSource())))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> trustToggle(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> transfer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), false))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> transfer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), true)))))
                .then(zone));
    }

    // ---- helpers ----

    private static ServerPlayer player(CommandSourceStack s) {
        return s.getEntity() instanceof ServerPlayer sp ? sp : null;
    }
    private static String dim(ServerPlayer sp) { return sp.level().dimension().identifier().toString(); }
    private static Claim standingClaim(ServerPlayer sp) {
        BlockPos p = sp.blockPosition();
        return ShopGuard.STORE.claimAt(dim(sp), p.getX(), p.getZ());
    }
    private static int notPlayer(CommandSourceStack s) {
        s.sendFailure(Component.literal("Run this as a player."));
        return 0;
    }

    // ---- /claim (bare): claim here + your claims + totals ----

    private static int info(CommandSourceStack s) {
        ServerPlayer sp = player(s);
        if (sp == null) return notPlayer(s);

        Claim here = standingClaim(sp);
        if (here == null) {
            s.sendSuccess(() -> Component.literal("Here: unclaimed.").withStyle(ChatFormatting.GRAY), false);
        } else if (here.owner.equals(sp.getUUID())) {
            int ord = ShopGuard.STORE.ordinalOf(here);
            s.sendSuccess(() -> Component.literal("Here: your claim #" + ord + " — "
                    + here.shape.count() + " blocks.").withStyle(ChatFormatting.AQUA), false);
        } else {
            s.sendSuccess(() -> Component.literal("Here: " + here.ownerName + "'s claim — "
                    + here.shape.count() + " blocks.").withStyle(ChatFormatting.AQUA), false);
        }

        List<Claim> mine = ShopGuard.STORE.byOwner(sp.getUUID());
        if (mine.isEmpty()) {
            s.sendSuccess(() -> Component.literal("You have no claims.").withStyle(ChatFormatting.GRAY), false);
        } else {
            s.sendSuccess(() -> Component.literal("Your claims (" + mine.size() + "):")
                    .withStyle(ChatFormatting.AQUA), false);
            int ord = 0;
            for (Claim c : mine) {
                int n = ++ord;
                s.sendSuccess(() -> Component.literal(" • #" + n + " — " + c.shape.count() + " blocks near "
                        + c.shape.minX() + ", " + c.shape.minZ() + " (" + c.dimension + ")"
                        + (c.outstandingPaid() > 0
                        ? " — paid " + ClaimPricing.money(c.paid)
                        + (c.refunded > 0 ? ", " + ClaimPricing.money(c.refunded) + " refunded" : "")
                        + ", " + ClaimPricing.money(c.outstandingPaid()) + " returnable"
                        : ""))
                        .withStyle(ChatFormatting.GRAY), false);
            }
            int total = ShopGuard.STORE.totalCellsOfOwner(sp.getUUID());
            String totalMsg = ProtectionHandler.isOp(sp)
                    ? "Total claimed: " + total + " blocks (admin — no limit)."
                    : "Total claimed: " + total + " / " + ShopGuard.CONFIG.maxTotalPerPlayer + " blocks.";
            s.sendSuccess(() -> Component.literal(totalMsg).withStyle(ChatFormatting.GRAY), false);
        }

        // The pricing derivation in force, so the cost is never a mystery. `/bal` and the shovel overlay
        // report the same numbers, and every constant behind them is a key in config/shopguard.json.
        ClaimPricing.describe(sp, line -> s.sendSuccess(() -> line, false));
        return 1;
    }

    // ---- /claim show: toggle border visibility ----

    private static int show(CommandSourceStack s) {
        ServerPlayer sp = player(s);
        if (sp == null) return notPlayer(s);
        boolean on = ClaimVisualizer.toggleZoneViewer(sp.getUUID());
        if (on) {
            String hint = ShopGuard.STORE.zones().isEmpty()
                    ? "Borders ON — no zones are defined, so you can claim anywhere. Claims: green = yours, orange = others."
                    : "Borders ON — zones dark red, your claims green, others orange.";
            s.sendSuccess(() -> Component.literal(hint).withStyle(ChatFormatting.GREEN), false);
        } else {
            s.sendSuccess(() -> Component.literal("Borders OFF.").withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    // ---- /claim remove: owner (ops: anyone's) ----

    /**
     * Release the claim underfoot.
     *
     * <p>For the owner this is a sale back to the server, so it pays a refund: half of what the claim
     * cost, less the refund fee, drawn from a daily allowance shared with the {@code /sell} faucet.
     * The price is the one recorded when the land was bought, never re-derived from the live inflation
     * factor — otherwise a player could buy cheap during a deflationary stretch and cash out during an
     * inflationary one.
     *
     * <p>The release is refused outright when the refund won't fit in today's allowance, because the
     * money model has no negative balances and no escrow: paying part would mean holding land against a
     * debt it can't represent, or silently forfeiting the rest. An op removing someone else's claim is a
     * grief response and never pays out.
     */
    private static int remove(CommandSourceStack s) {
        ServerPlayer sp = player(s);
        if (sp == null) return notPlayer(s);
        Claim c = standingClaim(sp);
        if (c == null) {
            s.sendFailure(Component.literal("Stand inside a claim to remove it."));
            return 0;
        }
        boolean isOwner = c.owner.equals(sp.getUUID());
        if (!isOwner && !ProtectionHandler.isOp(sp)) {
            s.sendFailure(Component.literal("That claim isn't yours."));
            return 0;
        }
        String label = isOwner
                ? "your claim #" + ShopGuard.STORE.ordinalOf(c)
                : c.ownerName + "'s claim";

        ClaimPricing.Result refund = isOwner
                ? ClaimPricing.refund(sp, c)
                : ClaimPricing.Result.free();
        if (!refund.ok()) {
            s.sendFailure(Component.literal(refund.reason()));
            return 0;
        }
        ShopGuard.STORE.remove(c.id);
        ClaimVisualizer.refresh(sp.level());
        s.sendSuccess(() -> Component.literal("Removed " + label + "."
                        + (refund.amount() > 0 ? " Refunded " + ClaimPricing.money(refund.amount()) + "." : ""))
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    // ---- /claim trust <player>: toggle ----

    private static int trustToggle(CommandSourceStack s, ServerPlayer target) {
        ServerPlayer sp = player(s);
        if (sp == null) return notPlayer(s);
        Claim c = standingClaim(sp);
        if (c == null) {
            s.sendFailure(Component.literal("Stand inside your claim."));
            return 0;
        }
        if (!c.owner.equals(sp.getUUID()) && !ProtectionHandler.isOp(sp)) {
            s.sendFailure(Component.literal("That claim isn't yours."));
            return 0;
        }
        boolean added = c.trusted.add(target.getUUID());
        if (!added) c.trusted.remove(target.getUUID());
        ShopGuard.STORE.save();
        String name = target.getName().getString();
        s.sendSuccess(() -> Component.literal(name + (added ? " is now trusted" : " is no longer trusted")
                + " on this claim.").withStyle(added ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    // ---- /claim transfer <player> [confirm]: owner (ops: anyone's) ----

    /**
     * Hand the claim underfoot to another player. Without {@code confirm} this only describes what would
     * happen, so a mistyped name can't quietly hand a build away. What the previous owner is left with is
     * {@link Config#keepOldOwnerTrusted}: by default a clean handover (they lose access, like every other
     * land-claim mod); with the flag on they stay trusted and keep building.
     */
    private static int transfer(CommandSourceStack s, ServerPlayer target, boolean confirmed) {
        ServerPlayer sp = player(s);
        if (sp == null) return notPlayer(s);
        Claim c = standingClaim(sp);
        if (c == null) {
            s.sendFailure(Component.literal("Stand inside a claim to transfer it."));
            return 0;
        }
        boolean isOwner = c.owner.equals(sp.getUUID());
        if (!isOwner && !ProtectionHandler.isOp(sp)) {
            s.sendFailure(Component.literal("That claim isn't yours."));
            return 0;
        }
        String name = target.getName().getString();
        if (c.owner.equals(target.getUUID())) {
            s.sendFailure(Component.literal(name + " already owns this claim."));
            return 0;
        }
        // The recipient inherits the whole footprint, so their total limit has to cover it.
        int recipientTotal = ShopGuard.STORE.totalCellsOfOwner(target.getUUID()) + c.shape.count();
        if (!ProtectionHandler.isOp(target) && recipientTotal > ShopGuard.CONFIG.maxTotalPerPlayer) {
            s.sendFailure(Component.literal(name + " would go over their total claim limit ("
                    + ShopGuard.CONFIG.maxTotalPerPlayer + " blocks)."));
            return 0;
        }

        // Describe the claim before the owner changes, so the ordinal still refers to the sender's list.
        boolean keepTrusted = ShopGuard.CONFIG.keepOldOwnerTrusted;
        String label = isOwner ? "your claim #" + ShopGuard.STORE.ordinalOf(c) : c.ownerName + "'s claim";
        if (!confirmed) {
            s.sendSuccess(() -> Component.literal("Transfer " + label + " (" + c.shape.count()
                    + " blocks) to " + name + "? Re-run with: /claim transfer " + name + " confirm")
                    .withStyle(ChatFormatting.YELLOW), false);
            s.sendSuccess(() -> Component.literal(keepTrusted
                            ? "You'll keep build access (keepOldOwnerTrusted is on)."
                            : "You'll lose access to it (keepOldOwnerTrusted is off).")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        UUID prevOwner = c.owner;
        c.owner = target.getUUID();
        c.ownerName = name;
        if (keepTrusted) c.trusted.add(prevOwner);
        ShopGuard.STORE.save();
        ClaimVisualizer.refresh(sp.level());
        String tail = isOwner
                ? (keepTrusted ? " — you're now trusted on it." : " — you no longer have access to it.")
                : ".";
        s.sendSuccess(() -> Component.literal("Transferred " + label + " to " + name + tail)
                .withStyle(ChatFormatting.GREEN), false);
        if (target != sp) {
            target.sendSystemMessage(Component.literal(sp.getName().getString() + " transferred a claim to you ("
                    + c.shape.count() + " blocks). /claim lists it.").withStyle(ChatFormatting.GREEN));
        }
        return 1;
    }

    // ---- /claim zone ... (ops) ----

    private static int zoneAdd(CommandSourceStack s, BlockPos c1, BlockPos c2) {
        ServerPlayer sp = player(s);
        if (sp == null) return notPlayer(s);
        AdminZone z = ShopGuard.STORE.addZone(dim(sp), c1.getX(), c1.getZ(), c2.getX(), c2.getZ());
        ClaimVisualizer.refresh(sp.level());
        s.sendSuccess(() -> Component.literal("Zone #" + z.id + " set — " + z.shape.count() + " blocks.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int zoneList(CommandSourceStack s) {
        Collection<AdminZone> zones = ShopGuard.STORE.zones();
        if (zones.isEmpty()) {
            s.sendSuccess(() -> Component.literal("No claim zones — players may claim anywhere.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        s.sendSuccess(() -> Component.literal("Claim zones (" + zones.size() + "):").withStyle(ChatFormatting.AQUA), false);
        for (AdminZone z : zones) {
            s.sendSuccess(() -> Component.literal(" • #" + z.id + " — " + z.shape.count() + " blocks near "
                    + z.shape.minX() + ", " + z.shape.minZ() + " (" + z.dimension + ")")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int zoneRemove(CommandSourceStack s, long id) {
        if (!ShopGuard.STORE.removeZone(id)) {
            s.sendFailure(Component.literal("No zone #" + id + "."));
            return 0;
        }
        ServerPlayer sp = player(s);
        if (sp != null) ClaimVisualizer.refresh(sp.level());
        s.sendSuccess(() -> Component.literal("Removed claim zone #" + id + ".").withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }
}
