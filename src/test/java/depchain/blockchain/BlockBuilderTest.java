package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockBuilderTest {

    private static final String ALICE = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String BOB = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
    private static final String CHARLIE = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC";

    @Test
    void choosesHighestFeeEligibleHeadsAcrossAccounts() {
        Mempool mempool = new Mempool();
        PendingTransaction alice0 = add(mempool, 1L, tx(ALICE, BOB, 0, 10, 5, 0));   // fee offer 50
        PendingTransaction bob0 = add(mempool, 2L, tx(BOB, ALICE, 0, 20, 5, 0));     // fee offer 100
        PendingTransaction charlie0 = add(mempool, 3L, tx(CHARLIE, ALICE, 0, 15, 5, 0)); // fee offer 75

        BlockBuilder builder = new BlockBuilder(BlockBuilderConfig.byTxCountOnly(10));
        BlockCandidate candidate = builder.buildNextBlock(mempool, account -> 0L);

        assertEquals(List.of(bob0, charlie0, alice0), candidate.getTransactions());
        assertEquals(15L, candidate.getTotalGasLimit());
    }

    @Test
    void preservesNonceOrderWithinSameAccountEvenWhenLaterNoncePaysMore() {
        Mempool mempool = new Mempool();
        PendingTransaction alice0 = add(mempool, 1L, tx(ALICE, BOB, 0, 1, 21_000, 0));
        PendingTransaction alice1 = add(mempool, 2L, tx(ALICE, BOB, 1, 100, 21_000, 0));
        PendingTransaction bob0 = add(mempool, 3L, tx(BOB, ALICE, 0, 50, 21_000, 0));

        BlockBuilder builder = new BlockBuilder(BlockBuilderConfig.byTxCountOnly(10));
        BlockCandidate candidate = builder.buildNextBlock(mempool, account -> 0L);

        assertEquals(List.of(bob0, alice0, alice1), candidate.getTransactions());
    }

    @Test
    void greedyStopsWhenNextBestEligibleTransactionWouldExceedGasCap() {
        Mempool mempool = new Mempool();
        PendingTransaction bestFit = add(mempool, 1L, tx(ALICE, BOB, 0, 20, 5, 0));   // fee offer 100, gas 5
        PendingTransaction nextTooLarge = add(mempool, 2L, tx(BOB, ALICE, 0, 9, 10, 0)); // fee offer 90, gas 10
        add(mempool, 3L, tx(CHARLIE, ALICE, 0, 1, 1, 0)); // would fit, but builder should stop instead of skipping

        BlockBuilder builder = new BlockBuilder(new BlockBuilderConfig(10, 5));
        BlockCandidate candidate = builder.buildNextBlock(mempool, account -> 0L);

        assertEquals(List.of(bestFit), candidate.getTransactions());
        assertEquals(5L, candidate.getTotalGasLimit());
        assertTrue(mempool.containsRequestId(2L));
        assertTrue(mempool.containsRequestId(3L));
        assertEquals(3, mempool.size(), "builder must not mutate the mempool");
        assertNotNull(nextTooLarge);
    }

    @Test
    void respectsMaxTransactionsPerBlock() {
        Mempool mempool = new Mempool();
        PendingTransaction first = add(mempool, 1L, tx(ALICE, BOB, 0, 30, 1, 0));
        PendingTransaction second = add(mempool, 2L, tx(BOB, ALICE, 0, 20, 1, 0));
        add(mempool, 3L, tx(CHARLIE, ALICE, 0, 10, 1, 0));

        BlockBuilder builder = new BlockBuilder(BlockBuilderConfig.byTxCountOnly(2));
        BlockCandidate candidate = builder.buildNextBlock(mempool, account -> 0L);

        assertEquals(List.of(first, second), candidate.getTransactions());
        assertEquals(2L, candidate.getTotalGasLimit());
    }

    @Test
    void tieBreaksByArrivalSequenceThenRequestId() {
        Mempool mempool = new Mempool();
        PendingTransaction firstArrived = add(mempool, 10L, tx(ALICE, BOB, 0, 10, 1, 0));
        PendingTransaction secondArrived = add(mempool, 11L, tx(BOB, ALICE, 0, 10, 1, 0));

        BlockBuilder builder = new BlockBuilder(BlockBuilderConfig.byTxCountOnly(10));
        BlockCandidate candidate = builder.buildNextBlock(mempool, account -> 0L);

        assertEquals(List.of(firstArrived, secondArrived), candidate.getTransactions());
    }

    private static PendingTransaction add(Mempool mempool, long requestId, Transaction tx) {
        PendingTransaction pending = mempool.addVerifiedTransaction(
            requestId,
            tx.getFrom(),
            tx,
            ("req-" + requestId).getBytes(StandardCharsets.UTF_8)
        );
        assertNotNull(pending);
        return pending;
    }

    private static Transaction tx(String from, String to, long nonce, long gasPrice, long gasLimit, long value) {
        return new Transaction(from, to, nonce, value, gasPrice, gasLimit, null);
    }
}
