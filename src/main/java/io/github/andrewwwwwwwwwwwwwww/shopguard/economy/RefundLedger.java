package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How much refund allowance each player has spent today.
 *
 * <p>This is the server's pacing valve on its only on-demand money creation path. A player who paid
 * {@code paid} for a claim can only get {@code (paid - fee)} back, and only out of a daily budget equal
 * to {@code Config.claimRefundDailyLimit} — so releasing a large claim takes several days rather than
 * moving more money in one action than a full day of {@code /sell} can.
 *
 * <p>The file holds a single day, not a history: the epoch day and that day's per-player spend. A
 * rollover resets the map in memory, so the on-disk state can never grow, and a stale ledger from a
 * previous day is simply replaced.
 */
public final class RefundLedger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private long day = Long.MIN_VALUE;
    private final Map<UUID, Long> spent = new HashMap<>();
    private final Path path;

    public RefundLedger() {
        this(null);
    }

    /** Test seam: pin the ledger to a file instead of the config dir. */
    RefundLedger(Path path) {
        this.path = path;
    }

    public synchronized void load() {
        try {
            Path path = file();
            if (!Files.exists(path)) return;
            DayFile wrapper = GSON.fromJson(Files.readString(path), DayFile.class);
            if (wrapper == null) return;
            day = wrapper.day;
            spent.clear();
            if (wrapper.spent != null) {
                for (Map.Entry<String, Long> e : wrapper.spent.entrySet()) {
                    if (e.getValue() == null) continue;
                    try {
                        spent.put(UUID.fromString(e.getKey()), e.getValue());
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        } catch (Exception e) {
            ShopGuard.LOGGER.error("Failed to load the claim refund ledger; treating everyone as unspent", e);
            day = Long.MIN_VALUE;
            spent.clear();
        }
    }

    public synchronized void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            DayFile wrapper = new DayFile();
            wrapper.day = day;
            wrapper.spent = new HashMap<>();
            for (Map.Entry<UUID, Long> e : spent.entrySet()) {
                wrapper.spent.put(e.getKey().toString(), e.getValue());
            }
            Files.writeString(path, GSON.toJson(wrapper));
        } catch (IOException e) {
            ShopGuard.LOGGER.error("Failed to save the claim refund ledger", e);
        }
    }

    /** Today's unspent allowance for {@code player}, or {@code dailyLimit} when it isn't set. */
    public synchronized long allowanceLeft(UUID player, long dailyLimit) {
        rollOver();
        if (dailyLimit <= 0) return 0L;
        return Math.max(0L, dailyLimit - spent.getOrDefault(player, 0L));
    }

    /**
     * Record {@code amount} against today's allowance.
     *
     * <p>Call this <em>after</em> the money has actually been credited, and only then — it is the
     * durable record of a payout that happened, so a failed credit must not consume the budget. It is
     * still written before the next tick, so a crash in between loses at most the record of one refund,
     * which fails in the safe direction: the player keeps the land and the money, and the budget is
     * under-counted rather than over-counted.
     */
    public synchronized void record(UUID player, long amount) {
        if (amount <= 0) return;
        rollOver();
        spent.merge(player, amount, Long::sum);
        save();
    }

    /** Drop the ledger if the epoch day rolled over since it was last touched. */
    private void rollOver() {
        long today = LocalDate.now().toEpochDay();
        if (day == today) return;
        if (day != Long.MIN_VALUE) {
            ShopGuard.LOGGER.info("Claim refund allowance reset for epoch day {}", today);
        }
        day = today;
        spent.clear();
        save();
    }

    /** Forgets the recorded day so the next call treats the server as freshly booted. */
    public synchronized void reset() {
        day = Long.MIN_VALUE;
        spent.clear();
    }

    private Path file() {
        return path != null ? path
                : FabricLoader.getInstance().getConfigDir().resolve("shopguard").resolve("claim_refunds.json");
    }

    /** On-disk shape. */
    private static final class DayFile {
        long day = Long.MIN_VALUE;
        Map<String, Long> spent = new HashMap<>();
    }
}
