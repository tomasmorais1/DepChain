package depchain.blockchain;

import depchain.client.ClientProtocol;
import depchain.client.eth.TransactionEthHasher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orders a batch of verified client requests for inclusion in one consensus block.
 *
 * <p>Stage 2 PDF: higher transaction fee first within a block. Per sender, nonces must stay
 * strictly increasing — we group by {@code from}, sort each chain by nonce, then repeatedly pick
 * the <em>head</em> transaction with highest {@linkplain Transaction#maxFeeOffer() fee offer}
 * (tie-break: sender address, then nonce). This matches “fee priority” without executing a
 * higher nonce before a lower one for the same account.
 */
public final class TransactionBatchOrder {

    /** Among same-fee heads, order by sender then nonce (deterministic). */
    private static final Comparator<Transaction> PROPOSAL_HEAD_ORDER =
        Transaction.FEE_PRIORITY
            .thenComparing(
                t -> TransactionEthHasher.normalizeAddr(t.getFrom()),
                String.CASE_INSENSITIVE_ORDER
            )
            .thenComparingLong(Transaction::getNonce);

    private TransactionBatchOrder() {}

    public static List<ClientProtocol.Request> orderForProposal(List<ClientProtocol.Request> batch) {
        if (batch == null || batch.isEmpty()) {
            return batch;
        }
        if (batch.size() == 1) {
            return new ArrayList<>(batch);
        }
        Map<String, List<ClientProtocol.Request>> byFrom = new HashMap<>();
        for (ClientProtocol.Request req : batch) {
            Transaction tx = TransactionCommandCodec.decode(req.getString());
            if (tx == null) {
                continue;
            }
            String key = keyFrom(tx);
            byFrom.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
        }
        for (List<ClientProtocol.Request> chain : byFrom.values()) {
            chain.sort(
                Comparator.comparingLong(
                    r -> Objects.requireNonNull(TransactionCommandCodec.decode(r.getString())).getNonce()
                )
            );
        }
        List<ClientProtocol.Request> out = new ArrayList<>(batch.size());
        while (true) {
            ClientProtocol.Request bestReq = null;
            Transaction bestTx = null;
            String bestKey = null;
            for (Map.Entry<String, List<ClientProtocol.Request>> e : byFrom.entrySet()) {
                List<ClientProtocol.Request> chain = e.getValue();
                if (chain.isEmpty()) {
                    continue;
                }
                ClientProtocol.Request head = chain.get(0);
                Transaction tx = TransactionCommandCodec.decode(head.getString());
                if (tx == null) {
                    continue;
                }
                if (bestTx == null || PROPOSAL_HEAD_ORDER.compare(tx, bestTx) < 0) {
                    bestTx = tx;
                    bestReq = head;
                    bestKey = e.getKey();
                }
            }
            if (bestReq == null) {
                break;
            }
            Objects.requireNonNull(bestKey);
            byFrom.get(bestKey).remove(0);
            out.add(bestReq);
        }
        return out;
    }

    private static String keyFrom(Transaction tx) {
        return TransactionEthHasher.normalizeAddr(tx.getFrom()).toLowerCase();
    }
}
