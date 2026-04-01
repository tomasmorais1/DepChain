package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MempoolBlockBuilderIntegrationTest {

    private static final String ALICE = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String BOB = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    @Test
    void committedTransactionsAreRemovedAndNextNonceBecomesEligible() {
        Mempool mempool = new Mempool();
        PendingTransaction alice0 = add(mempool, 1L, tx(ALICE, BOB, 0, 10, 2, 0));
        PendingTransaction alice1 = add(mempool, 2L, tx(ALICE, BOB, 1, 50, 2, 0));
        PendingTransaction bob0 = add(mempool, 3L, tx(BOB, ALICE, 0, 20, 2, 0));

        BlockBuilder builder = new BlockBuilder(BlockBuilderConfig.byTxCountOnly(10));

        BlockCandidate round1 = builder.buildNextBlock(mempool, account -> 0L);
        assertEquals(List.of(bob0, alice0, alice1), round1.getTransactions());

        mempool.removeCommitted(List.of(bob0, alice0));

        BlockCandidate round2 = builder.buildNextBlock(mempool, account -> {
            if (ALICE.equals(account)) return 1L;
            if (BOB.equals(account)) return 1L;
            return 0L;
        });

        assertEquals(List.of(alice1), round2.getTransactions());
        assertEquals(1, mempool.size());
        assertTrue(mempool.containsRequestId(2L));
        assertFalse(mempool.containsRequestId(1L));
        assertFalse(mempool.containsRequestId(3L));
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
