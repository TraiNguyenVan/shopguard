package io.github.andrewwwwwwwwwwwwwww.shopguard.claim;

import net.minecraft.nbt.CompoundTag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One player-owned claim: an owner, a dimension, and a full-height {@link ClaimShape} footprint. */
public final class Claim {
    public final long id;
    public UUID owner;
    public String ownerName;
    public String dimension;
    public ClaimShape shape;
    public final Set<UUID> trusted = new HashSet<>();

    /**
     * Money burned to acquire the area currently in this claim, and how much of it has already been
     * returned. Both are zero on a claim that was never charged for — every claim that predates claim
     * pricing, and every claim an op placed.
     *
     * <p>The price is <em>recorded</em> at purchase rather than recomputed from the live inflation factor,
     * which is the whole point: the factor is a market rate that moves, so re-deriving a refund from it
     * would let a player buy land during a deflationary stretch and cash out during an inflationary one.
     * What is left to give back is therefore always {@code paid - refunded}, and a refund is always
     * smaller than that by {@code Config.claimRefundFeeRate}.
     *
     * <p>Carving reduces {@code paid} in proportion to the area removed even though it pays no cash, so
     * that carving a claim down and then releasing it cannot return more than the surviving area cost.
     */
    public long paid;
    public long refunded;

    public Claim(long id, UUID owner, String ownerName, String dimension) {
        this(id, owner, ownerName, dimension, new ClaimShape());
    }

    private Claim(long id, UUID owner, String ownerName, String dimension, ClaimShape shape) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.dimension = dimension;
        this.shape = shape;
    }

    public boolean contains(int x, int z) { return shape.contains(x, z); }

    /** May this player modify blocks / open containers here? Owner or explicitly trusted. */
    public boolean mayBuild(UUID player) { return player.equals(owner) || trusted.contains(player); }

    /** What this claim could still return to its owner, before the refund fee and the daily allowance. */
    public long outstandingPaid() { return Math.max(0L, paid - refunded); }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("Id", id);
        t.putString("Owner", owner.toString());
        t.putString("OwnerName", ownerName == null ? "" : ownerName);
        t.putString("Dim", dimension);
        t.put("Shape", shape.save());
        if (paid != 0L) t.putLong("Paid", paid);
        if (refunded != 0L) t.putLong("Refunded", refunded);
        if (!trusted.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (UUID u : trusted) { if (sb.length() > 0) sb.append(','); sb.append(u); }
            t.putString("Trusted", sb.toString());
        }
        return t;
    }

    public static Claim load(CompoundTag t) {
        long id = t.getLongOr("Id", 0);
        UUID owner = UUID.fromString(t.getStringOr("Owner", new UUID(0, 0).toString()));
        String ownerName = t.getStringOr("OwnerName", "?");
        String dim = t.getStringOr("Dim", "minecraft:overworld");
        ClaimShape shape = ClaimShape.load(t.getCompoundOrEmpty("Shape"));
        Claim c = new Claim(id, owner, ownerName, dim, shape);
        // Absent on every claim written before claim pricing existed, and on op-placed claims: both are
        // genuinely worth zero, so a missing tag and a stored 0 mean the same thing.
        c.paid = Math.max(0L, t.getLongOr("Paid", 0L));
        c.refunded = Math.max(0L, t.getLongOr("Refunded", 0L));
        if (c.refunded > c.paid) c.refunded = c.paid;
        String trusted = t.getStringOr("Trusted", "");
        if (!trusted.isEmpty()) {
            for (String s : trusted.split(",")) {
                try { c.trusted.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            }
        }
        return c;
    }
}
