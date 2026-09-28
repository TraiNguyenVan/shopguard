package io.github.andrewwwwwwwwwwwwwww.shopguard.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily refund allowance ledger.
 *
 * <p>This is the pacing valve on the server's only on-demand money creation path, and it fails in the two
 * worst possible directions: a ledger that reads back empty hands out unlimited refunds, and one that
 * reads back full blocks every release forever. The persistence shape is therefore worth testing
 * directly rather than trusting a round trip through the running server.
 */
class RefundLedgerTest {

    private static final long DAILY_LIMIT = 2_500L;
    private static final UUID ADA = UUID.fromString("8c2493d3-de84-30ce-9f66-8c0ed9f78d50");
    private static final UUID TRAI = UUID.fromString("4d13f044-e358-308e-8aae-9004131b5686");

    @Test
    void aFreshLedgerGrantsTheWholeAllowance(@TempDir Path dir) {
        RefundLedger ledger = new RefundLedger(dir.resolve("claim_refunds.json"));
        assertEquals(DAILY_LIMIT, ledger.allowanceLeft(ADA, DAILY_LIMIT));
        assertEquals(DAILY_LIMIT, ledger.allowanceLeft(TRAI, DAILY_LIMIT));
    }

    @Test
    void spendingIsTrackedPerPlayerNotGlobally(@TempDir Path dir) {
        RefundLedger ledger = new RefundLedger(dir.resolve("claim_refunds.json"));
        ledger.record(ADA, 2_000);
        assertEquals(500, ledger.allowanceLeft(ADA, DAILY_LIMIT));
        // One player's refund must not consume anyone else's budget — that would let a rich player deny
        // everyone else the ability to release their own land.
        assertEquals(DAILY_LIMIT, ledger.allowanceLeft(TRAI, DAILY_LIMIT));
    }

    @Test
    void spendAccumulatesAcrossSeveralRefunds(@TempDir Path dir) {
        RefundLedger ledger = new RefundLedger(dir.resolve("claim_refunds.json"));
        ledger.record(ADA, 1_000);
        ledger.record(ADA, 1_000);
        assertEquals(500, ledger.allowanceLeft(ADA, DAILY_LIMIT));
    }

    @Test
    void spendingSurvivesAReload(@TempDir Path dir) throws IOException {
        // The regression this guards: the on-disk shape is {"day":…,"spent":{…}}, and reading it back as a
        // flat map silently produced an empty ledger — i.e. a refund allowance that never depletes.
        Path file = dir.resolve("claim_refunds.json");
        RefundLedger first = new RefundLedger(file);
        first.record(ADA, 2_500);
        first.record(TRAI, 750);

        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("\"day\""), "the epoch day must be persisted: " + onDisk);
        assertTrue(onDisk.contains("\"spent\""), "the spend map must be persisted: " + onDisk);

        RefundLedger second = new RefundLedger(file);
        second.load();
        assertEquals(0, second.allowanceLeft(ADA, DAILY_LIMIT), "a fully spent player stays spent");
        assertEquals(1_750, second.allowanceLeft(TRAI, DAILY_LIMIT));
    }

    @Test
    void reloadingTheSameDayDoesNotResetTheBudget(@TempDir Path dir) {
        Path file = dir.resolve("claim_refunds.json");
        RefundLedger first = new RefundLedger(file);
        first.record(ADA, 1_000);

        RefundLedger second = new RefundLedger(file);
        second.load();
        assertEquals(1_500, second.allowanceLeft(ADA, DAILY_LIMIT),
                "a server restart on the same day must not hand back the allowance");
    }

    @Test
    void aStaleLedgerFromAPreviousDayIsDiscarded(@TempDir Path dir) throws IOException {
        // The file only ever holds one day, so yesterday's spend can never carry over and lock a player out.
        Path file = dir.resolve("claim_refunds.json");
        RefundLedger ledger = new RefundLedger(file);
        ledger.record(ADA, 2_500);
        Files.writeString(file, Files.readString(file)
                .replace(Long.toString(LocalDate.now().toEpochDay()),
                        Long.toString(LocalDate.now().toEpochDay() - 3)));

        RefundLedger reloaded = new RefundLedger(file);
        reloaded.load();
        assertEquals(DAILY_LIMIT, reloaded.allowanceLeft(ADA, DAILY_LIMIT));
    }

    @Test
    void aCorruptFileFailsOpenRatherThanLockingEveryoneOut(@TempDir Path dir) throws IOException {
        // An unparseable ledger must not read back as "everyone is spent", which would make every release
        // permanently impossible. Treating it as unspent is the recoverable direction: at worst a few
        // refunds exceed one day's pace.
        Path file = dir.resolve("claim_refunds.json");
        Files.writeString(file, "{ this is not json");
        RefundLedger ledger = new RefundLedger(file);
        ledger.load();
        assertEquals(DAILY_LIMIT, ledger.allowanceLeft(ADA, DAILY_LIMIT));
    }

    @Test
    void allowanceNeverGoesNegative(@TempDir Path dir) {
        RefundLedger ledger = new RefundLedger(dir.resolve("claim_refunds.json"));
        ledger.record(ADA, 99_999);
        assertEquals(0, ledger.allowanceLeft(ADA, DAILY_LIMIT));
    }

    @Test
    void aZeroLimitMeansNoRefundsArePossible(@TempDir Path dir) {
        RefundLedger ledger = new RefundLedger(dir.resolve("claim_refunds.json"));
        assertEquals(0, ledger.allowanceLeft(ADA, 0));
        assertEquals(0, ledger.allowanceLeft(ADA, -5));
    }
}
