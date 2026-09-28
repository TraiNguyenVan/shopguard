package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

/**
 * The claim-pricing derivation, as pure arithmetic with no Minecraft or EconomyCraft types, so it can be
 * unit tested directly. Every constant is supplied by the caller ({@link Params}, normally a view over
 * {@code Config}) — nothing here reads global state.
 *
 * <p>The three questions this answers:
 * <ul>
 *   <li>{@link #factor} — how expensive is a column right now, relative to when land was priced at
 *       {@code perBlock}? This is the inflation term.</li>
 *   <li>{@link #charge} — what does adding {@code netNewColumns} cost?</li>
 *   <li>{@link #refund} — what comes back when a claim is released, and is the release allowed at all?</li>
 * </ul>
 */
public final class ClaimCostMath {
    private ClaimCostMath() {}

    /** The configurable constants of the derivation. */
    public record Params(
            double perBlock,
            long baseFee,
            double referenceMultiplier,
            double minFactor,
            double maxFactor,
            double refundFeeRate,
            double carveRefundRate
    ) {
        public Params {
            if (perBlock < 0) perBlock = 0;
            if (baseFee < 0) baseFee = 0;
            if (!(referenceMultiplier > 0)) referenceMultiplier = 1;
            if (!(minFactor > 0)) minFactor = Double.MIN_VALUE;
            if (maxFactor < minFactor) maxFactor = minFactor;
            refundFeeRate = clamp01(refundFeeRate);
            carveRefundRate = clamp01(carveRefundRate);
        }

        private static double clamp01(double v) {
            if (Double.isNaN(v) || v < 0) return 0;
            return Math.min(v, 1);
        }
    }

    /**
     * {@code f = clamp(inflationMultiplier / referenceMultiplier, minFactor, maxFactor)}.
     *
     * <p>{@code f == 1} means the live inflation multiplier is exactly the reference, i.e. the
     * configured per-block rate applies unscaled. A non-positive or non-finite multiplier (which is
     * what EconomyCraft reports when {@code startingBalance} is unset or nobody is active) is treated as
     * the reference, so a broken signal leaves the price at its baseline instead of at the floor.
     */
    public static double factor(double inflationMultiplier, Params p) {
        double m = inflationMultiplier;
        if (!(m > 0) || Double.isInfinite(m)) m = p.referenceMultiplier();
        double f = m / p.referenceMultiplier();
        if (Double.isNaN(f)) return p.minFactor();
        return Math.clamp(f, p.minFactor(), p.maxFactor());
    }

    /** The effective per-column price at this factor, i.e. {@code perBlock * f}. */
    public static double ratePerBlock(double inflationMultiplier, Params p) {
        return p.perBlock() * factor(inflationMultiplier, p);
    }

    /**
     * {@code baseFee + ceil(netNewColumns * ratePerBlock)}.
     *
     * <p>Only <em>net new</em> columns are billed. Touching claims a player already owns are
     * flood-merged into the result, so billing the merged total would re-charge the whole region every
     * time it is expanded; a merge that adds no new columns is free.
     */
    public static long charge(int netNewColumns, double inflationMultiplier, Params p) {
        if (netNewColumns <= 0) return 0L;
        double cost = netNewColumns * ratePerBlock(inflationMultiplier, p);
        if (!(cost > 0)) return p.baseFee();
        double rounded = Math.ceil(cost);
        if (rounded >= Long.MAX_VALUE - p.baseFee()) return Long.MAX_VALUE;
        return p.baseFee() + (long) rounded;
    }

    /**
     * What a carve gives back as cash, and what it removes from the claim's outstanding {@code paid}.
     *
     * <p>Carving pays nothing by default, but the claim's {@code paid} is always reduced in proportion to
     * the area removed. That proportionality is what stops the obvious exploit: if {@code paid} survived a
     * carve untouched, a player could claim a large area, carve it down to one column for free, and still
     * release the claim for the full original amount.
     *
     * @return {@code {cashBack, removedFromPaid}}; both zero when nothing was removed.
     */
    public static long[] carve(long paid, int cellsBefore, int cellsAfter, Params p) {
        int removed = cellsBefore - cellsAfter;
        if (paid <= 0 || removed <= 0 || cellsBefore <= 0) return new long[]{0L, 0L};
        long removedPaid = (long) Math.floor(paid * ((double) removed / (double) cellsBefore));
        if (removedPaid > paid) removedPaid = paid;
        long cash = (long) Math.floor(removedPaid * p.carveRefundRate());
        if (cash > removedPaid) cash = removedPaid;
        return new long[]{cash, removedPaid};
    }

    /**
     * The largest refund a claim with {@code outstandingPaid} could return, before the daily allowance
     * is applied: the outstanding amount less the refund fee. The fee is the permanent burn — without it,
     * claim-then-release would be free and the charge would be a liquidity charge with no sink at all.
     *
     * <p>Bounded by {@code outstandingPaid} by construction, so no configuration can make a refund
     * exceed what was paid.
     */
    public static long maxRefund(long outstandingPaid, Params p) {
        if (outstandingPaid <= 0) return 0L;
        double amount = outstandingPaid * (1.0 - p.refundFeeRate());
        if (!(amount > 0)) return 0L;
        if (amount >= outstandingPaid) return outstandingPaid;
        return (long) Math.floor(amount);
    }

    /**
     * Whether {@code outstandingPaid} may be released today, given the daily allowance still unspent.
     *
     * <p>Deliberately all-or-nothing. EconomyCraft balances cannot go negative and there is no escrow or
     * scheduled payment, so a part-refund would either hold the land against a debt the money model
     * cannot represent or silently forfeit the remainder. Refusing leaves the player their land and their
     * money, which is recoverable; either alternative is not.
     */
    public static boolean refundable(long outstandingPaid, long allowanceLeft, Params p) {
        long want = maxRefund(outstandingPaid, p);
        if (want <= 0) return true;   // nothing owed — releasing is always allowed
        return allowanceLeft >= want;
    }

    /** What a release actually pays today: the full refund, or nothing if the allowance is too small. */
    public static long refund(long outstandingPaid, long allowanceLeft, Params p) {
        if (!refundable(outstandingPaid, allowanceLeft, p)) return 0L;
        return maxRefund(outstandingPaid, p);
    }

    /** The largest footprint a player can buy with {@code balance} at this factor, or 0 if none. */
    public static int affordableColumns(long balance, double inflationMultiplier, Params p) {
        if (balance <= p.baseFee()) return 0;
        double rate = ratePerBlock(inflationMultiplier, p);
        if (!(rate > 0)) return Integer.MAX_VALUE;
        double cols = (balance - p.baseFee()) / rate;
        if (cols >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.floor(cols);
    }
}
