package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import depchain.client.ClientProtocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockBatcherTest {

    @Test
    void greedyStopsWhenNextTxExceedsGasCap() {
        String alice = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String bob = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

        // Fee order uses maxFeeOffer = gasPrice*gasLimit.
        // Ensure tx1 is ordered before tx2, but only tx1 fits the cap.
        Transaction tx1 = new Transaction(alice, bob, 0, 0, 20, 5, null); // offer=100, fits cap=5
        Transaction tx2 = new Transaction(bob, alice, 0, 0, 9, 10, null); // offer=90, would exceed cap

        List<ClientProtocol.Request> drained = new ArrayList<>();
        drained.add(req(1, tx1));
        drained.add(req(2, tx2));

        BlockBatcher.Result r = BlockBatcher.selectGreedyByGasCap(drained, 64, 5);
        assertEquals(1, r.selected().size());
        assertEquals(1L, r.selected().get(0).getRequestId());
        assertEquals(1, r.leftover().size());
        assertEquals(2L, r.leftover().get(0).getRequestId());
    }

    private static ClientProtocol.Request req(long id, Transaction tx) {
        return new ClientProtocol.Request(
            id,
            TransactionCommandCodec.encode(tx),
            null,
            null,
            null,
            ClientProtocol.TYPE_ETH_REQUEST
        );
    }
}

