package depchain.blockchain;

import depchain.client.ClientProtocol;
import java.util.ArrayList;
import java.util.List;

/**
 * Leader-side batch builder: fee+nonce order first, then greedy fill up to a per-block gas cap.
 * <p>Greedy policy: include highest-priority txs until the next tx would exceed the cap; do not
 * skip "too big" txs to fit smaller ones later.
 */
public final class BlockBatcher {
    private BlockBatcher() {}

    public record Result(List<ClientProtocol.Request> selected, List<ClientProtocol.Request> leftover) {}

    public static Result selectGreedyByGasCap(
        List<ClientProtocol.Request> drained,
        int maxTxs,
        long maxGasPerBlock
    ) {
        if (drained == null || drained.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        if (maxTxs <= 0 || maxGasPerBlock <= 0) {
            return new Result(List.of(), List.copyOf(drained));
        }

        List<ClientProtocol.Request> ordered = TransactionBatchOrder.orderForProposal(drained);

        List<ClientProtocol.Request> selected = new ArrayList<>();
        long gasAcc = 0L;
        int i = 0;
        for (; i < ordered.size() && selected.size() < maxTxs; i++) {
            ClientProtocol.Request req = ordered.get(i);
            Transaction tx = TransactionCommandCodec.decode(req.getString());
            if (tx == null) {
                // Should not happen for verified pool; treat as leftover.
                break;
            }
            long g = tx.getGasLimit();
            if (gasAcc + g > maxGasPerBlock) {
                break; // greedy stop
            }
            gasAcc += g;
            selected.add(req);
        }

        List<ClientProtocol.Request> leftover = new ArrayList<>();
        // Whatever we didn't include (and what we couldn't parse) goes back to mempool.
        for (int j = i; j < ordered.size(); j++) {
            leftover.add(ordered.get(j));
        }

        return new Result(List.copyOf(selected), List.copyOf(leftover));
    }
}

