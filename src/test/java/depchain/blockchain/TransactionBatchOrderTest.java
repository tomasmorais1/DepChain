package depchain.blockchain;

import depchain.client.ClientProtocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionBatchOrderTest {

    @Test
    void singleSender_preservesNonceOrderRegardlessOfFeeInBatch() {
        String alice = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String bob = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
        List<ClientProtocol.Request> batch = new ArrayList<>();
        batch.add(req(1, tx(alice, bob, 0, 0, 1, 100_000L)));
        batch.add(req(2, tx(alice, bob, 1, 0, 10, 21_000L)));
        batch.add(req(3, tx(alice, bob, 2, 0, 100, 21_000L)));

        List<ClientProtocol.Request> ordered = TransactionBatchOrder.orderForProposal(batch);
        assertEquals(3, ordered.size());
        assertEquals(1L, ordered.get(0).getRequestId());
        assertEquals(2L, ordered.get(1).getRequestId());
        assertEquals(3L, ordered.get(2).getRequestId());
    }

    @Test
    void twoSenders_higherFeeHeadExecutedFirst() {
        String alice = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String bob = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
        List<ClientProtocol.Request> batch = new ArrayList<>();
        batch.add(req(1, tx(alice, bob, 0, 0, 1, 21_000L)));
        batch.add(req(2, tx(bob, alice, 0, 0, 100, 21_000L)));

        List<ClientProtocol.Request> ordered = TransactionBatchOrder.orderForProposal(batch);
        assertEquals(2L, ordered.get(0).getRequestId());
        assertEquals(1L, ordered.get(1).getRequestId());
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

    private static Transaction tx(
        String from,
        String to,
        long nonce,
        long value,
        long gasPrice,
        long gasLimit
    ) {
        return new Transaction(from, to, nonce, value, gasPrice, gasLimit, null);
    }
}
