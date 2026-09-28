package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim-pricing derivation.
 *
 * <p>The numbers below are the figures the design was priced against, so they double as a regression
 * test on the policy: if a change moves what a 63-block or 400-block claim costs, or what a release
 * returns, one of these fails and the pricing conversation has to happen again deliberately.
 */
class ClaimCostMathTest {

    /** The shipped defaults from {@code Config}. */
    private static final ClaimCostMath.Params DEFAULTS =
            new ClaimCostMath.Params(2.5, 0L, 7.36, 0.25, 4.0, 0.5, 0.0);

    /** The live inflation multiplier at the time the design was priced: median 7360 / startingBalance 1000. */
    private static final double INFLATION_NOW = 7.36;

    // ---- factor ----

    @Test
    void factorIsOneAtTheReferenceMultiplier() {
        assertEquals(1.0, ClaimCostMath.factor(INFLATION_NOW, DEFAULTS), 1e-9);
    }

    @Test
    void factorMovesProportionallyWithMoneySupply() {
        // Halving the money supply halves the price of land; doubling it doubles the price.
        assertEquals(0.5, ClaimCostMath.factor(INFLATION_NOW / 2, DEFAULTS), 1e-9);
        assertEquals(2.0, ClaimCostMath.factor(INFLATION_NOW * 2, DEFAULTS), 1e-9);
    }

    @Test
    void factorIsClampedAtBothEnds() {
        // Raw multiplier 0.5 against a 7.36 reference is 0.068 — well under the floor.
        assertEquals(0.25, ClaimCostMath.factor(0.5, DEFAULTS), 1e-9);
        // Raw multiplier 100 against a 7.36 reference is 13.6 — well over the ceiling.
        assertEquals(4.0, ClaimCostMath.factor(100.0, DEFAULTS), 1e-9);
    }

    @Test
    void aBrokenInflationSignalFallsBackToTheBaselineNotTheFloor() {
        // EconomyCraft reports 0 when startingBalance is unset or nobody is active. That must not be
        // mistaken for a collapse in the money supply, which would make every claim nearly free.
        assertEquals(1.0, ClaimCostMath.factor(0.0, DEFAULTS), 1e-9);
        assertEquals(1.0, ClaimCostMath.factor(-5.0, DEFAULTS), 1e-9);
        assertEquals(1.0, ClaimCostMath.factor(Double.NaN, DEFAULTS), 1e-9);
    }

    // ---- charge ----

    @Test
    void chargeMatchesThePricedExamples() {
        assertEquals(158, ClaimCostMath.charge(63, INFLATION_NOW, DEFAULTS));   // Traimairap's existing claim
        assertEquals(675, ClaimCostMath.charge(270, INFLATION_NOW, DEFAULTS));  // Cap_Cap_Sever's largest
        assertEquals(1_000, ClaimCostMath.charge(400, INFLATION_NOW, DEFAULTS));
        assertEquals(6_250, ClaimCostMath.charge(2_500, INFLATION_NOW, DEFAULTS));
        assertEquals(25_000, ClaimCostMath.charge(10_000, INFLATION_NOW, DEFAULTS));
    }

    @Test
    void aMergeThatAddsNoColumnsIsFree() {
        // The whole point of billing net-new cells: re-selecting area a merge already covers costs nothing.
        assertEquals(0, ClaimCostMath.charge(0, INFLATION_NOW, DEFAULTS));
        assertEquals(0, ClaimCostMath.charge(-5, INFLATION_NOW, DEFAULTS));
    }

    @Test
    void chargeRoundsUpSoAFractionalRateStillCostsSomething() {
        // 1 column at the clamped floor: 2.5 * 0.25 = 0.625, which must not floor to a free claim.
        assertEquals(1, ClaimCostMath.charge(1, 0.5, DEFAULTS));
    }

    @Test
    void theBaseFeeAppliesOnceRegardlessOfSize() {
        ClaimCostMath.Params withFee = new ClaimCostMath.Params(2.5, 500L, 7.36, 0.25, 4.0, 0.5, 0.0);
        assertEquals(1_500, ClaimCostMath.charge(400, INFLATION_NOW, withFee));
    }

    @Test
    void aMergeThatAddsNoNewAreaIsFreeEvenWithABaseFee() {
        // Re-selecting ground a merge already covers did nothing, so it must not be billed — not even
        // the flat fee. Charging here would punish the exact interaction the net-new rule exists to
        // protect: expanding a claim by selecting a rectangle that overlaps what you already hold.
        ClaimCostMath.Params withFee = new ClaimCostMath.Params(2.5, 500L, 7.36, 0.25, 4.0, 0.5, 0.0);
        assertEquals(0, ClaimCostMath.charge(0, INFLATION_NOW, withFee));
    }

    @Test
    void aZeroRateMeansNoChargeRatherThanACrash() {
        ClaimCostMath.Params free = new ClaimCostMath.Params(0.0, 0L, 7.36, 0.25, 4.0, 0.5, 0.0);
        assertEquals(0, ClaimCostMath.charge(10_000, INFLATION_NOW, free));
    }

    // ---- refund ----

    @Test
    void refundReturnsHalfAndBurnsTheFee() {
        assertEquals(500, ClaimCostMath.maxRefund(1_000, DEFAULTS));
        assertEquals(8_550, ClaimCostMath.maxRefund(17_100, DEFAULTS));
        assertEquals(79, ClaimCostMath.maxRefund(158, DEFAULTS));
    }

    @Test
    void aRefundCanNeverExceedWhatWasPaid() {
        // Zero fee: the whole amount comes back, but not a unit more.
        ClaimCostMath.Params noFee = new ClaimCostMath.Params(2.5, 0L, 7.36, 0.25, 4.0, 0.0, 0.0);
        assertEquals(1_000, ClaimCostMath.maxRefund(1_000, noFee));
        assertTrue(ClaimCostMath.maxRefund(1_000, DEFAULTS) <= 1_000);
        assertEquals(0, ClaimCostMath.maxRefund(0, DEFAULTS));
        assertEquals(0, ClaimCostMath.maxRefund(-100, DEFAULTS));
    }

    @Test
    void aFullFeeMeansNoRefundAndNoObstruction() {
        ClaimCostMath.Params fullFee = new ClaimCostMath.Params(2.5, 0L, 7.36, 0.25, 4.0, 1.0, 0.0);
        assertEquals(0, ClaimCostMath.maxRefund(1_000, fullFee));
        // Nothing is owed, so the release is never blocked by the allowance.
        assertTrue(ClaimCostMath.refundable(1_000, 0, fullFee));
    }

    @Test
    void theDailyAllowanceGatesTheWholeRefundAndNeverPartPays() {
        // A 17100 claim wants 8550 back; with only 2500 of allowance it is refused outright.
        assertFalse(ClaimCostMath.refundable(17_100, 2_500, DEFAULTS));
        assertEquals(0, ClaimCostMath.refund(17_100, 2_500, DEFAULTS));
        // With enough allowance it pays in full, in one action.
        assertTrue(ClaimCostMath.refundable(17_100, 8_550, DEFAULTS));
        assertEquals(8_550, ClaimCostMath.refund(17_100, 8_550, DEFAULTS));
        assertEquals(8_550, ClaimCostMath.refund(17_100, 999_999, DEFAULTS));
    }

    @Test
    void theAllowanceBoundaryIsInclusive() {
        // Exactly enough allowance must succeed — otherwise a player could never release a claim whose
        // refund lands precisely on the daily limit.
        assertTrue(ClaimCostMath.refundable(1_000, 500, DEFAULTS));
        assertFalse(ClaimCostMath.refundable(1_002, 500, DEFAULTS));
    }

    @Test
    void theFeeIsFlooredSoARefundNeverRoundsUpIntoTheNextUnit() {
        // 1001 paid refunds 500, not 501. Flooring (rather than rounding) is what guarantees a refund
        // stays strictly below the fee boundary, so the 500-of-allowance case above is reachable at all.
        assertEquals(500, ClaimCostMath.maxRefund(1_001, DEFAULTS));
        assertEquals(500, ClaimCostMath.maxRefund(1_000, DEFAULTS));
    }

    @Test
    void anUnpaidClaimIsAlwaysReleasable() {
        // Every claim that predates claim pricing, and every op-placed claim, has paid = 0.
        assertEquals(0, ClaimCostMath.maxRefund(0, DEFAULTS));
        assertTrue(ClaimCostMath.refundable(0, 0, DEFAULTS));
    }

    // ---- carve ----

    @Test
    void carvePaysNothingByDefaultButStillShrinksPaid() {
        // 1000 paid over 400 columns, carving away 300 of them: 750 of paid is unearned, and 0 of it
        // comes back as cash. This proportionality is what stops claim-then-carve-then-release.
        long[] result = ClaimCostMath.carve(1_000, 400, 100, DEFAULTS);
        assertEquals(0, result[0], "carve pays no cash at the default rate");
        assertEquals(750, result[1], "paid attributable to the removed area is released");
    }

    @Test
    void carveRefundRatePaysOutOfTheRemovedPortionOnly() {
        ClaimCostMath.Params halfBack = new ClaimCostMath.Params(2.5, 0L, 7.36, 0.25, 4.0, 0.5, 0.5);
        long[] result = ClaimCostMath.carve(1_000, 400, 100, halfBack);
        assertEquals(375, result[0], "half of the 750 attributable to the removed area");
        assertEquals(750, result[1]);
    }

    @Test
    void carvingNothingChangesNothing() {
        long[] result = ClaimCostMath.carve(1_000, 400, 400, DEFAULTS);
        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
        assertEquals(0, ClaimCostMath.carve(0, 400, 0, DEFAULTS)[1]);
    }

    // ---- affordability ----

    @Test
    void theMoneyWallBindsWellBeforeTheBlockCap() {
        // This is the finding that decided the design: at 2.5 per column, maxTotalPerPlayer (40000) and
        // even maxClaimArea (10000) are unreachable by every current balance, so the block caps are
        // decorative and land is limited by wealth instead.
        assertEquals(383, ClaimCostMath.affordableColumns(958, INFLATION_NOW, DEFAULTS));    // Traimairap
        assertEquals(387, ClaimCostMath.affordableColumns(968, INFLATION_NOW, DEFAULTS));    // Phutai
        assertEquals(2_944, ClaimCostMath.affordableColumns(7_360, INFLATION_NOW, DEFAULTS));  // Cap_Cap_Sever
        assertEquals(5_150, ClaimCostMath.affordableColumns(12_875, INFLATION_NOW, DEFAULTS)); // yukakami
        assertEquals(6_840, ClaimCostMath.affordableColumns(17_100, INFLATION_NOW, DEFAULTS)); // Adelph
    }

    @Test
    void affordabilityHalvesWhenTheMoneySupplyDoubles() {
        assertEquals(3_420, ClaimCostMath.affordableColumns(17_100, INFLATION_NOW * 2, DEFAULTS));
    }

    @Test
    void aPoorPlayerCanStillAffordAStarterPlot() {
        // The floor exists so inflation can't price land out of reach entirely. Even pinned at the
        // ceiling, the poorest current balance buys a usable 95-column plot.
        assertEquals(95, ClaimCostMath.affordableColumns(958, 1_000_000, DEFAULTS));
        assertTrue(ClaimCostMath.affordableColumns(958, 1_000_000, DEFAULTS) >= 63,
                "must still cover the largest claim anyone currently holds");
    }

    // ---- parameter hardening ----

    @Test
    void nonsensicalParametersAreCorrectedRatherThanPropagated() {
        ClaimCostMath.Params junk = new ClaimCostMath.Params(
                -5.0, -100L, 0.0, -1.0, -2.0, 5.0, -3.0);
        assertEquals(0.0, junk.perBlock());
        assertEquals(0L, junk.baseFee());
        assertEquals(1.0, junk.refundFeeRate(), 1e-9, "a fee above 100% is clamped, not obeyed");
        assertEquals(0.0, junk.carveRefundRate(), 1e-9);
        assertTrue(junk.maxFactor() >= junk.minFactor());
        // And the derivation still returns something finite.
        assertTrue(Double.isFinite(ClaimCostMath.factor(7.36, junk)));
        assertTrue(ClaimCostMath.charge(1_000, 7.36, junk) >= 0);
    }

    @Test
    void anEnormousClaimSaturatesRatherThanOverflowing() {
        // A rate and size whose product exceeds Long.MAX_VALUE must saturate, not wrap negative — a
        // wrapped charge would read as a huge credit and hand the player money for claiming land.
        ClaimCostMath.Params absurd = new ClaimCostMath.Params(
                1e17, 0L, 7.36, 0.25, 4.0, 0.5, 0.0);
        assertEquals(Long.MAX_VALUE, ClaimCostMath.charge(1_000, INFLATION_NOW, absurd));
    }

    @Test
    void aLargeButSaneChargeStillAddsUpExactly() {
        // The overflow guard must not fire early: this product is large yet comfortably representable, and
        // the base fee has to be added on top of it without being swallowed.
        ClaimCostMath.Params large = new ClaimCostMath.Params(
                1e12, 1_000L, 7.36, 0.25, 4.0, 0.5, 0.0);
        assertEquals(100_000_000_000_001_000L, ClaimCostMath.charge(100_000, INFLATION_NOW, large));
    }
}
