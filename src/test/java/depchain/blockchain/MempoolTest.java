package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MempoolTest {

    private static final String ALICE = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String BOB = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    @Test
    void rejectsDuplicateRequestId() {
        Mempool mempool = new Mempool();

        PendingTransaction first = mempool.addVerifiedTransaction(
            1L, "alice", tx(ALICE, BOB, 0, 5, 21000, 1000), bytes("req-1")
        );
        PendingTransaction duplicateRequestId = mempool.addVerifiedTransaction(
            1L, "alice", tx(ALICE, BOB, 1, 5, 21000, 1000), bytes("req-1-dup")
        );

        assertNotNull(first);
        assertNull(duplicateRequestId);
        assertEquals(1, mempool.size());
    }

    @Test
    void rejectsDuplicateSenderAndNonceEvenWithDifferentRequestId() {
        Mempool mempool = new Mempool();

        PendingTransaction first = mempool.addVerifiedTransaction(
            1L, "alice", tx(ALICE, BOB, 0, 5, 21000, 1000), bytes("req-1")
        );
        PendingTransaction duplicateNonce = mempool.addVerifiedTransaction(
            2L, "alice", tx(ALICE, BOB, 0, 7, 21000, 1000), bytes("req-2")
        );

        assertNotNull(first);
        assertNull(duplicateNonce);
        assertEquals(1, mempool.size());
    }

    @Test
    void eligibleHeadsRespectCommittedNoncePerAccount() {
        Mempool mempool = new Mempool();

        PendingTransaction alice0 = mempool.addVerifiedTransaction(
            1L, "alice", tx(ALICE, BOB, 0, 10, 21000, 1000), bytes("a0")
        );
        PendingTransaction alice1 = mempool.addVerifiedTransaction(
            2L, "alice", tx(ALICE, BOB, 1, 50, 21000, 1000), bytes("a1")
        );
        PendingTransaction bob0 = mempool.addVerifiedTransaction(
            3L, "bob", tx(BOB, ALICE, 0, 20, 21000, 1000), bytes("b0")
        );

        List<PendingTransaction> eligibleAtZero = mempool.getEligibleHeads(account -> 0L);
        assertEquals(2, eligibleAtZero.size());
        assertTrue(eligibleAtZero.contains(alice0));
        assertTrue(eligibleAtZero.contains(bob0));
        assertFalse(eligibleAtZero.contains(alice1));

        List<PendingTransaction> eligibleAfterAlice0 = mempool.getEligibleHeads(account -> {
            if (ALICE.equals(account)) return 1L;
            return 0L;
        });
        assertEquals(2, eligibleAfterAlice0.size());
        assertTrue(eligibleAfterAlice0.contains(alice1));
        assertTrue(eligibleAfterAlice0.contains(bob0));
        assertFalse(eligibleAfterAlice0.contains(alice0));
    }

    @Test
    void removeCommittedRemovesOnlyCommittedTransactions() {
        Mempool mempool = new Mempool();

        PendingTransaction alice0 = mempool.addVerifiedTransaction(
            1L, "alice", tx(ALICE, BOB, 0, 10, 21000, 1000), bytes("a0")
        );
        PendingTransaction alice1 = mempool.addVerifiedTransaction(
            2L, "alice", tx(ALICE, BOB, 1, 11, 21000, 1000), bytes("a1")
        );
        PendingTransaction bob0 = mempool.addVerifiedTransaction(
            3L, "bob", tx(BOB, ALICE, 0, 12, 21000, 1000), bytes("b0")
        );

        mempool.removeCommitted(List.of(alice0, bob0));

        assertEquals(1, mempool.size());
        assertFalse(mempool.containsRequestId(1L));
        assertFalse(mempool.containsRequestId(3L));
        assertTrue(mempool.containsRequestId(2L));

        List<PendingTransaction> eligible = mempool.getEligibleHeads(account -> {
            if (ALICE.equals(account)) return 1L;
            return 0L;
        });
        assertEquals(List.of(alice1), eligible);
    }

    @Test
    void snapshotIsIndependentFromLiveMempool() {
        Mempool mempool = new Mempool();
        PendingTransaction alice0 = mempool.addVerifiedTransaction(
            1L, "alice", tx(ALICE, BOB, 0, 10, 21000, 1000), bytes("a0")
        );

        var snapshot = mempool.snapshotPendingByAccount();
        snapshot.get(ALICE).clear();

        assertEquals(1, mempool.size());
        List<PendingTransaction> eligible = mempool.getEligibleHeads(account -> 0L);
        assertEquals(List.of(alice0), eligible);
    }

    private static Transaction tx(String from, String to, long nonce, long gasPrice, long gasLimit, long value) {
        return new Transaction(from, to, nonce, value, gasPrice, gasLimit, null);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
