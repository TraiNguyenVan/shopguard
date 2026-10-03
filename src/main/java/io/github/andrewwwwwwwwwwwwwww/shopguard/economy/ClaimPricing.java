package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

import io.github.andrewwwwwwwwwwwwwww.shopguard.Config;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import io.github.andrewwwwwwwwwwwwwww.shopguard.claim.Claim;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;


/**
 * Applies claim pricing to the shovel: quoting it, charging for it, scaling it on carve, and giving it
 * back on release.
 *
 * <p>All of the arithmetic lives in {@link ClaimCostMath}; this class is the operational half — reading
 * the live config, talking to the economy backend, and turning the results into messages. It is written
 * so that <em>nothing happens unless it fully succeeds</em>: a claim is only ever charged after every
 * validation has passed, and a claim is only ever released after the money has been credited.
 *
 * <p>When pricing is off, or EconomyCraft isn't installed, every method here is a no-op that reports
 * success with a zero amount. That keeps the claim tool free of economy branches.
 */
public final class ClaimPricing {
    private ClaimPricing() {}

    /** The outcome of a charge or refund attempt. */
    public record Result(boolean ok, long amount, String reason) {
        static Result ok(long amount) { return new Result(true, amount, null); }
        static Result fail(String reason) { return new Result(false, 0L, reason); }
        public static Result free() { return new Result(true, 0L, null); }
    }

    /** The current config as math parameters. */
    public static ClaimCostMath.Params params() {
        Config c = ShopGuard.CONFIG;
        return new ClaimCostMath.Params(
                c.claimCostPerBlock,
                c.claimCostBaseFee,
                c.claimCostReferenceMultiplier,
                c.claimCostMinFactor,
                c.claimCostMaxFactor,
                c.claimRefundFeeRate,
                c.claimCarveRefundRate);
    }

    /** True when new claim area actually costs money on this server right now. */
    public static boolean charging() {
        return ShopGuard.CONFIG.claimCostEnabled && ClaimEconomy.backend() != null;
    }

    /** The economy's live inflation multiplier, or 0 when there is no economy to read. */
    public static double inflation() {
        ClaimEconomy.Backend backend = ClaimEconomy.backend();
        return backend == null ? 0.0 : backend.inflationMultiplier();
    }

    /** The current inflation factor {@code f}, valid even with no economy attached (returns the floor). */
    public static double factor() {
        return ClaimCostMath.factor(inflation(), params());
    }

    public static double ratePerBlock() {
        return ClaimCostMath.ratePerBlock(inflation(), params());
    }

    /** What {@code netNewColumns} costs at the undiscounted rate. Zero when pricing is off. */
    public static long quote(int netNewColumns) {
        if (!charging()) return 0L;
        return ClaimCostMath.charge(netNewColumns, inflation(), params());
    }

    /**
     * What {@code netNewColumns} costs <em>this player</em>, with their party's discount applied.
     *
     * <p>Every quoted figure has to go through here rather than {@link #quote(int)}. A discount applied only
     * in {@link #charge} would leave the shovel's price overlay and {@code /claim} advertising the full rate
     * to a Monarchy owner, who would then be charged less than the tool promised — technically fine for the
     * player, but it makes the advertised rate a lie and hides the buff entirely.
     */
    public static long quote(ServerPlayer player, int netNewColumns) {
        if (!charging()) return 0L;
        return ClaimCostMath.charge(netNewColumns, inflation(), params(), discountFor(player));
    }

    /** This player's claim-cost multiplier: Monarchy halves it, everyone else pays full price. */
    public static double discountFor(ServerPlayer player) {
        return player == null ? 1.0 : io.github.andrewwwwwwwwwwwwwww.shopguard.faction.PlayerFactions
                .claimCostMultiplier(player.getUUID());
    }

    /** Whether this player gets a party discount, for saying so in a message. */
    public static boolean discounted(ServerPlayer player) {
        return discountFor(player) < 1.0;
    }

    private static boolean exempt(ServerPlayer player) {
        return ShopGuard.CONFIG.claimCostFreeForOps
                && io.github.andrewwwwwwwwwwwwwww.shopguard.ProtectionHandler.isOp(player);
    }

    /**
     * Burn the price of {@code netNewColumns} new columns from {@code player}.
     *
     * <p>Called only after the overlap, zone and area checks have all passed and before the claim is
     * written, so a rejected claim is never charged. An insufficient balance fails the whole claim —
     * {@link ClaimEconomy.Backend#charge} is all-or-nothing, so there is no partial debit to unwind.
     */
    public static Result charge(ServerPlayer player, int netNewColumns) {
        if (netNewColumns <= 0) return Result.free();
        if (!charging()) return Result.free();
        if (exempt(player)) return Result.free();

        long cost = quote(player, netNewColumns);
        if (cost <= 0) return Result.free();

        ClaimEconomy.Backend backend = ClaimEconomy.backend();
        if (!backend.charge(player.getUUID(), cost)) {
            return Result.fail("You can't afford that — it costs " + money(cost)
                    + " and you have " + money(backend.balance(player.getUUID())) + ".");
        }
        return Result.ok(cost);
    }

    /**
     * Fold a carve's effect on a claim's money: cash back now, and the reduction in outstanding `paid`.
     *
     * <p>Returns the amount of cash the carve earned. The caller must actually pay it — see
     * {@link #creditCarveRefund} — the carve itself only adjusts the claim's ledger.
     */
    public static long[] carve(Claim claim, int cellsBefore, int cellsAfter) {
        long[] result = ClaimCostMath.carve(claim.paid, cellsBefore, cellsAfter, params());
        claim.paid = Math.max(0L, claim.paid - result[1]);
        return new long[]{result[0]};
    }

    /**
     * Pay out what a carve earned, per {@code claimCarveRefundRate}.
     *
     * <p>Deliberately <strong>not</strong> gated on the daily refund allowance, which is why enabling a
     * non-zero carve rate logs a warning: carving becomes a second, unpaced way for the server to create
     * money. Charging it against {@code claimRefundDailyLimit} instead would be the consistent choice, but
     * it silently swallows a player's money when the day's allowance is spent, and the allowance is not
     * built to arbitrate between two different payouts. Left as a documented, warned-about policy choice
     * rather than silently coupling the two.
     *
     * <p>Called only on a carve that is actually being kept. A carve that gets rolled back must not pay.
     */
    public static boolean creditCarveRefund(ServerPlayer player, Claim claim, long amount) {
        if (amount <= 0) return true;
        ClaimEconomy.Backend backend = ClaimEconomy.backend();
        if (backend == null) return false;
        return backend.credit(claim.owner, amount);
    }

    /** The per-player daily ceiling on claim refunds, read from ShopGuard's own config. */
    public static long refundDailyLimit() {
        return Math.max(0L, ShopGuard.CONFIG.claimRefundDailyLimit);
    }

    /**
     * What releasing {@code claim} would pay, and whether it can be paid today. Used both for the
     * preview in {@code /claim} and for the actual release.
     */
    public static long[] refundPreview(Claim claim) {
        if (!ShopGuard.CONFIG.claimRefundEnabled || ClaimEconomy.backend() == null) {
            return new long[]{0L, 0L};
        }
        long outstanding = claim.outstandingPaid();
        ClaimCostMath.Params p = params();
        long allowance = ShopGuard.REFUND_LEDGER.allowanceLeft(claim.owner, refundDailyLimit());
        long amount = ClaimCostMath.refund(outstanding, allowance, p);
        return new long[]{amount, allowance};
    }

    /**
     * Credit the refund for a claim that is about to be released, then mark the claim as settled.
     *
     * <p>All-or-nothing by design: if the outstanding amount exceeds today's unspent allowance the
     * release is refused and the player keeps both the land and the money. Partial refunds are not
     * possible in this money model — balances cannot go negative and there is no escrow — so paying part
     * would mean either holding land against an unrepresentable debt or forfeiting the rest.
     */
    public static Result refund(ServerPlayer player, Claim claim) {
        if (!ShopGuard.CONFIG.claimRefundEnabled) return Result.free();
        ClaimEconomy.Backend backend = ClaimEconomy.backend();
        if (backend == null) return Result.free();
        // The money always goes to the claim's owner, so only they can trigger it. An op or admin removing
        // someone's land is a grief response, not a sale, and must not pay the owner out for it.
        if (!claim.owner.equals(player.getUUID())) return Result.free();
        if (claim.outstandingPaid() <= 0) return Result.free();

        long outstanding = claim.outstandingPaid();
        long limit = refundDailyLimit();
        long allowance = ShopGuard.REFUND_LEDGER.allowanceLeft(claim.owner, limit);
        ClaimCostMath.Params p = params();

        if (!ClaimCostMath.refundable(outstanding, allowance, p)) {
            long want = ClaimCostMath.maxRefund(outstanding, p);
            if (limit <= 0) {
                return Result.fail("Refunds are enabled but claimRefundDailyLimit is 0, so no claim can "
                        + "ever be released with a refund. Ask an admin to fix it.");
            }
            return Result.fail("This claim is worth " + money(want) + " back, but your refund allowance "
                    + "today is only " + money(allowance) + " (limit " + money(limit) + " a day). "
                    + "Carve it down or come back tomorrow.");
        }

        long amount = ClaimCostMath.refund(outstanding, allowance, p);
        if (amount <= 0) return Result.free();
        if (!backend.credit(claim.owner, amount)) {
            return Result.fail("Couldn't pay the refund — nothing was changed.");
        }
        claim.refunded = Math.min(claim.paid, claim.refunded + amount);
        ShopGuard.REFUND_LEDGER.record(claim.owner, amount);
        return Result.ok(amount);
    }

    /** Format money through the economy when there is one, so units match what players see elsewhere. */
    public static String money(long amount) {
        ClaimEconomy.Backend backend = ClaimEconomy.backend();
        return backend == null ? String.valueOf(amount) : backend.formatMoney(amount);
    }

    /**
     * The transparency block: the derivation actually in force, plus what this player could buy and what
     * they could get back. Appended to the bare {@code /claim} so the price is never a mystery — a
     * player should be able to see the factor, the rate and their remaining allowance without opening a
     * config file or asking an admin.
     */
    public static void describe(ServerPlayer player, java.util.function.Consumer<Component> sink) {
        Config c = ShopGuard.CONFIG;
        ClaimEconomy.Backend backend = ClaimEconomy.backend();

        if (backend == null) {
            sink.accept(Component.literal("Claim cost: off (no economy mod installed).")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        if (!c.claimCostEnabled) {
            sink.accept(Component.literal("Claim cost: off (claimCostEnabled = false).")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        double infl = inflation();
        ClaimCostMath.Params p = params();
        double f = ClaimCostMath.factor(infl, p);

        double own = discountFor(player);
        sink.accept(Component.literal("Claim cost: "
                        + money(ClaimCostMath.charge(1, infl, p, own)) + " per block"
                        + (c.claimCostBaseFee > 0 ? " + " + money(c.claimCostBaseFee) + " per claim" : "")
                        + "  [rate " + trim(ratePerBlock()) + " x factor " + trim(f)
                        + (own < 1.0 ? " x your party " + trim(own) : "") + "]")
                .withStyle(own < 1.0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        sink.accept(Component.literal("  inflation " + trim(infl) + "x / reference "
                        + trim(c.claimCostReferenceMultiplier) + "x, clamped to ["
                        + trim(c.claimCostMinFactor) + ", " + trim(c.claimCostMaxFactor) + "]"
                        + (c.claimCostFreeForOps && io.github.andrewwwwwwwwwwwwwww.shopguard.ProtectionHandler.isOp(player)
                        ? " — you are an op, so claims are free" : "")
                        + (f <= c.claimCostMinFactor ? " — at the floor" : "")
                        + (f >= c.claimCostMaxFactor ? " — at the ceiling" : ""))
                .withStyle(ChatFormatting.DARK_GRAY));

        long balance = backend.balance(player.getUUID());
        int affordable = ClaimCostMath.affordableColumns(balance, infl, p);
        sink.accept(Component.literal("  you can afford " + (affordable == Integer.MAX_VALUE
                        ? "an unlimited claim" : affordable + " blocks")
                        + " at your balance of " + money(balance) + ".")
                .withStyle(ChatFormatting.DARK_GRAY));

        if (c.claimRefundEnabled) {
            long limit = refundDailyLimit();
            long left = ShopGuard.REFUND_LEDGER.allowanceLeft(player.getUUID(), limit);
            sink.accept(Component.literal("  refunds return " + Math.round((1 - c.claimRefundFeeRate) * 100)
                            + "% of what a claim cost, up to " + money(limit)
                            + " a day (claimRefundDailyLimit) — " + money(left) + " left today.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            sink.accept(Component.literal("  refunds are off (claimRefundEnabled = false).")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Trim a double for display: 2.5 rather than 2.5000000000000004. */
    public static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format("%.2f", v);
    }
}
