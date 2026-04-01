package depchain.blockchain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Verified pending transactions waiting to be proposed in a block.
 *
 * Invariants:
 * - at most one pending tx per requestId
 * - at most one pending tx per (sender, nonce)
 * - block eligibility is based on the next expected nonce for each account
 *
 * This class does NOT execute transactions and does NOT mutate committed state.
 */
public final class Mempool {

    /** senderAddress -> (nonce -> pending tx) */
    private final Map<String, NavigableMap<Long, PendingTransaction>> pendingByAccount =
        new HashMap<>();

    /** requestIds currently present in the mempool. */
    private final Set<Long> requestIds = new HashSet<>();

    /** Monotonic sequence for deterministic tie-breaking. */
    private long nextArrivalSequence = 0L;

    /**
     * Adds a verified transaction to the mempool.
     *
     * @return the inserted pending transaction, or {@code null} if the tx is a duplicate
     *     requestId / duplicate (sender,nonce) / invalid input.
     */
    public synchronized PendingTransaction addVerifiedTransaction(
        long requestId,
        String clientId,
        Transaction tx,
        byte[] canonicalBytes
    ) {
        if (tx == null) {
            return null;
        }
        if (requestIds.contains(requestId)) {
            return null;
        }

        pendingByAccount.putIfAbsent(tx.getFrom(), new TreeMap<>());
        NavigableMap<Long, PendingTransaction> byNonce = pendingByAccount.get(tx.getFrom());

        if (byNonce.containsKey(tx.getNonce())) {
            return null;
        }

        PendingTransaction pending = new PendingTransaction(
            requestId,
            clientId,
            tx,
            canonicalBytes,
            nextArrivalSequence++
        );

        byNonce.put(tx.getNonce(), pending);
        requestIds.add(requestId);
        return pending;
    }

    public synchronized boolean containsRequestId(long requestId) {
        return requestIds.contains(requestId);
    }

    public synchronized boolean hasPendingTransactions() {
        return !pendingByAccount.isEmpty();
    }

    /**
     * Returns the currently eligible "head" transaction for each account.
     *
     * A transaction is eligible for an account iff its nonce equals the account's
     * next expected committed nonce.
     */
    public synchronized List<PendingTransaction> getEligibleHeads(NonceProvider nonceProvider) {
        List<PendingTransaction> eligible = new ArrayList<>();
        for (Map.Entry<String, NavigableMap<Long, PendingTransaction>> entry : pendingByAccount.entrySet()) {
            String account = entry.getKey();
            long expectedNonce = nonceProvider.getNextExpectedNonce(account);
            PendingTransaction tx = entry.getValue().get(expectedNonce);
            if (tx != null) {
                eligible.add(tx);
            }
        }
        return eligible;
    }

    /** Removes transactions that were committed in a decided block. */
    public synchronized void removeCommitted(List<PendingTransaction> committed) {
        if (committed == null || committed.isEmpty()) {
            return;
        }

        for (PendingTransaction tx : committed) {
            NavigableMap<Long, PendingTransaction> byNonce = pendingByAccount.get(tx.getSenderAddress());
            if (byNonce == null) {
                continue;
            }

            PendingTransaction removed = byNonce.remove(tx.getNonce());
            if (removed != null) {
                requestIds.remove(removed.getRequestId());
            }

            if (byNonce.isEmpty()) {
                pendingByAccount.remove(tx.getSenderAddress());
            }
        }
    }

    /**
     * Returns a safe snapshot for block-building simulation.
     *
     * The returned structure can be mutated by the caller without affecting the real mempool.
     */
    public synchronized Map<String, NavigableMap<Long, PendingTransaction>> snapshotPendingByAccount() {
        Map<String, NavigableMap<Long, PendingTransaction>> copy = new HashMap<>();
        for (Map.Entry<String, NavigableMap<Long, PendingTransaction>> entry : pendingByAccount.entrySet()) {
            copy.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
        return copy;
    }

    /** Optional helper for debugging/tests. */
    public synchronized int size() {
        int total = 0;
        for (NavigableMap<Long, PendingTransaction> byNonce : pendingByAccount.values()) {
            total += byNonce.size();
        }
        return total;
    }
}
