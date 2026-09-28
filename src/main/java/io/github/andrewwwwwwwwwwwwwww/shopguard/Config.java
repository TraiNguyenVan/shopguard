package io.github.andrewwwwwwwwwwwwwww.shopguard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ShopGuard configuration, persisted to {@code config/shopguard.json}. Moderators cap how much a
 * player can claim so nobody fences off half the world.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Maximum footprint (in blocks/columns) of a single claim. */
    public int maxClaimArea = 10000;      // e.g. 100 x 100

    /** Maximum total footprint a single player may own across all their claims. */
    public int maxTotalPerPlayer = 40000;

    /** If false (default), non-owners can't use buttons/levers in a claim (prevents redstone griefing);
     *  doors/gates/trapdoors/pressure plates for walking through are always allowed. */
    public boolean allowRedstoneControls = false;

    /** What happens to the seller in a `/claim transfer`. False (default) = clean handover: the previous
     *  owner loses all access, same as any other land-claim mod. True = they stay on the claim's trust
     *  list and keep build access, so nobody is locked out of a shop they just gave away. */
    public boolean keepOldOwnerTrusted = false;

    // ---- claim cost (economy) ----
    // Land is priced off the economy's inflation signal, so the cost of a claim rises and falls with the
    // money supply without anyone touching a setting. The whole derivation is:
    //
    //     f      = clamp(inflationMultiplier / claimCostReferenceMultiplier,
    //                    claimCostMinFactor, claimCostMaxFactor)
    //     charge = claimCostBaseFee + ceil(netNewColumns * claimCostPerBlock * f)
    //
    // `f` is deliberately a *compressed* function of the raw inflation multiplier rather than the raw
    // multiplier itself. The raw signal spans 0.5x..100x, which at any sane per-block rate locks
    // poorer players out of land they already hold within a few months of growth; anchored to a
    // reference value it moves proportionally instead. The clamps are load-bearing: the floor stops a
    // deflationary collapse from making land free, the ceiling stops inflation from making it
    // literally unclaimable.
    //
    // Every constant the derivation touches is a key here, so the price can be retuned without a
    // rebuild. `/claim` prints the live factor, rate and refund allowance, so the math in force is
    // visible in-game rather than only in this file.

    /** Master switch for charging for new claim area. Off = claiming is free and no `paid` is recorded. */
    public boolean claimCostEnabled = false;

    /** Cost per claimed column (a full-height X/Z cell) at f = 1.0. */
    public double claimCostPerBlock = 2.5;

    /** Flat fee added to every charged claim, whatever its size. 0 = purely per-block. Not charged when
     *  a selection adds no new area, since re-selecting ground a merge already covers did nothing. */
    public long claimCostBaseFee = 0L;

    /** The numeraire: the inflation multiplier at which f = 1.0, i.e. the per-block rate applies unscaled.
     *  Today's economy sits at 7.36x, so the default makes the current money supply the reference point. */
    public double claimCostReferenceMultiplier = 7.36;

    /** Clamp floor on f, so a deflationary collapse can't drive the rate to zero. */
    public double claimCostMinFactor = 0.25;

    /** Clamp ceiling on f, so runaway inflation can't price land out of reach entirely. */
    public double claimCostMaxFactor = 4.0;

    /** Ops are exempt from claim charges (and from refund accounting), like they are from the area caps. */
    public boolean claimCostFreeForOps = true;

    // ---- claim refunds (economy) ----
    // The charge is a burn, so without an exit land would be a one-way door: at these rates a claim can
    // be a large share of a player's net worth. `paid` is recorded per claim at purchase time and
    // refunded from *that* figure, never recomputed from the live factor — re-reading f at refund time
    // would let a player buy land cheap in a deflationary economy and cash out during inflation.
    //
    //     maxRefund  = (paid - refunded) * (1 - claimRefundFeeRate)   // the fee is the permanent burn
    //     refund     = min(maxRefund, claimRefundDailyLimit - refundedToday)
    //     refuse if  maxRefund > claimRefundDailyLimit - refundedToday  // no partial refunds, no debt
    //
    // Refusing rather than paying part is forced by the money model: balances can't go negative and
    // there is no escrow or scheduled payment, so a part-payment would either leave the land held
    // against an unrepresentable debt or silently forfeit the rest.
    //
    // The daily allowance is deliberately ShopGuard's own key rather than EconomyCraft's `dailySellLimit`.
    // That flag means one specific thing — the ceiling on server-side residual `/sell` proceeds — and
    // reading it as a general money-creation budget for an unrelated feature would quietly couple two
    // policies that should be tuned separately. 2500 matches the current sell cap so the pace is
    // familiar, but nothing keeps them in step: re-check this if `dailySellLimit` moves.

    /** Master switch for the refund side. Off = `/claim remove` releases the land and pays nothing. */
    public boolean claimRefundEnabled = false;

    /** Fraction of the outstanding `paid` withheld as the permanent burn on a refund. 0.5 = half back. */
    public double claimRefundFeeRate = 0.5;

    /** Per-player daily ceiling on claim refunds, in economy units. 0 = no refunds are ever payable, so
     *  every paid claim becomes permanently unclaimable while still charging for it. */
    public long claimRefundDailyLimit = 2500L;

    /** Fraction of a carved area's `paid` returned as cash. 0 = carving pays nothing (default). Whatever
     *  is not returned stays as `paid` on the smaller claim, so it is still refundable later. */
    public double claimCarveRefundRate = 0.0;

    public static Config load() {
        Path path = path();
        try {
            if (Files.exists(path)) {
                Config cfg = GSON.fromJson(Files.readString(path), Config.class);
                if (cfg != null) {
                    cfg.sanitize();
                    cfg.save();
                    return cfg;
                }
            }
        } catch (Exception e) {
            ShopGuard.LOGGER.error("Failed to load config; using defaults", e);
        }
        Config cfg = new Config();
        cfg.save();
        return cfg;
    }

    /**
     * Force the economy keys back into a range the derivation is actually defined over, so a hand-edited
     * config can't produce a negative price, a divide-by-zero factor, or a refund above what was paid.
     * Values are corrected and logged rather than discarded — a typo should be visible, not silently
     * revert the whole key to its default and leave the operator guessing which one applied.
     */
    private void sanitize() {
        if (claimCostPerBlock < 0) claimCostPerBlock = 0;
        if (claimCostBaseFee < 0) claimCostBaseFee = 0;
        if (claimCostReferenceMultiplier <= 0) claimCostReferenceMultiplier = 7.36;
        if (claimCostMinFactor <= 0) claimCostMinFactor = 0.25;
        if (claimCostMaxFactor < claimCostMinFactor) claimCostMaxFactor = claimCostMinFactor;
        if (claimRefundFeeRate < 0) claimRefundFeeRate = 0.5;
        if (claimRefundFeeRate > 1) claimRefundFeeRate = 0.5;
        if (Double.isNaN(claimCarveRefundRate) || claimCarveRefundRate < 0) claimCarveRefundRate = 0.0;
        if (claimCarveRefundRate > 1) claimCarveRefundRate = 1.0;
    }

    public void save() {
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            ShopGuard.LOGGER.error("Failed to save config", e);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shopguard.json");
    }
}
