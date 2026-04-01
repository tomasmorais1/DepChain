package depchain.blockchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.PriorityQueue;

/**
 * Builds the next candidate block from the mempool without mutating the mempool.
 *
 * Ordering rules:
 * - same account: strict nonce order
 * - different accounts: higher fee priority first
 * - deterministic tie-breaks: arrivalSequence, then requestId
 */
public final class BlockBuilder {

    private static final Comparator<PendingTransaction> PRIORITY =
        Comparator.comparingLong(PendingTransaction::getFeePriority).reversed()
            .thenComparingLong(PendingTransaction::getArrivalSequence)
            .thenComparingLong(PendingTransaction::getRequestId);

    private final BlockBuilderConfig config;

    public BlockBuilder(BlockBuilderConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        this.config = config;
    }

    public BlockBuilderConfig getConfig() {
        return config;
    }

    public BlockCandidate buildNextBlock(Mempool mempool, NonceProvider nonceProvider) {
        if (mempool == null || nonceProvider == null) {
            throw new IllegalArgumentException("mempool and nonceProvider cannot be null");
        }

        Map<String, NavigableMap<Long, PendingTransaction>> simulated =
            mempool.snapshotPendingByAccount();

        if (simulated.isEmpty()) {
            return new BlockCandidate(List.of(), 0L);
        }

        Map<String, Long> nextExpectedNonce = new HashMap<>();
        PriorityQueue<PendingTransaction> eligible = new PriorityQueue<>(PRIORITY);

        for (Map.Entry<String, NavigableMap<Long, PendingTransaction>> entry : simulated.entrySet()) {
            String account = entry.getKey();
            long expected = nonceProvider.getNextExpectedNonce(account);
            nextExpectedNonce.put(account, expected);

            PendingTransaction head = entry.getValue().get(expected);
            if (head != null) {
                eligible.add(head);
            }
        }

        List<PendingTransaction> selected = new ArrayList<>();
        long totalGasLimit = 0L;

        while (!eligible.isEmpty()) {
            if (selected.size() >= config.getMaxTransactionsPerBlock()) {
                break;
            }

            PendingTransaction candidate = eligible.poll();
            Transaction tx = candidate.getTransaction();

            if (config.hasGasLimit()) {
                long candidateGas = tx.getGasLimit();
                if (wouldOverflow(totalGasLimit, candidateGas, config.getMaxBlockGas())) {
                    // Recommended merge policy: greedy stop once the next best eligible tx no longer fits.
                    break;
                }
            }

            selected.add(candidate);
            totalGasLimit = safeAdd(totalGasLimit, tx.getGasLimit());

            String sender = candidate.getSenderAddress();
            long nonce = candidate.getNonce();

            NavigableMap<Long, PendingTransaction> byNonce = simulated.get(sender);
            if (byNonce == null) {
                continue;
            }

            byNonce.remove(nonce);

            long expectedNext = nonce + 1;
            nextExpectedNonce.put(sender, expectedNext);

            PendingTransaction nextTx = byNonce.get(expectedNext);
            if (nextTx != null) {
                eligible.add(nextTx);
            }
        }

        return new BlockCandidate(selected, totalGasLimit);
    }

    private static boolean wouldOverflow(long current, long add, long max) {
        long sum = safeAdd(current, add);
        return sum > max;
    }

    private static long safeAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
