package depchain.blockchain;

import depchain.consensus.Block;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extra Byzantine-leader tests: a replica should reject any proposed batch
 * containing commands that were never verified locally, were tampered with,
 * or are not even valid transaction encodings.
 */
class LeaderInvalidProposalValidationTest {

    /**
     * Mirrors the same predicate used by BlockchainMember block validation:
     * every tx in the proposed batch must match a locally verified request id
     * and decode into a valid Transaction.
     */
    private static boolean isVerifiedClientBatch(Map<Long, String> verifiedClientRequests, Block block) {
        TxBatchPayload batch = TxBatchPayload.fromBytes(block.getPayload());
        if (batch == null || batch.getItems().isEmpty()) {
            return true;
        }
        for (TxBatchPayload.TxItem item : batch.getItems()) {
            if (!Objects.equals(verifiedClientRequests.get(item.requestId()), item.command())) {
                return false;
            }
            if (TransactionCommandCodec.decode(item.command()) == null) {
                return false;
            }
        }
        return true;
    }

    @Test
    void honestVerifiedBatch_isAccepted() {
        Transaction tx = tx(0, 10, 2);
        String cmd = TransactionCommandCodec.encode(tx);
        Map<Long, String> verified = new HashMap<>();
        verified.put(100L, cmd);

        Block block = new Block(0, new TxBatchPayload(List.of(new TxBatchPayload.TxItem(100L, cmd))).toBytes());

        assertTrue(isVerifiedClientBatch(verified, block));
    }

    @Test
    void byzantineLeader_tamperedCommandForKnownRequestId_isRejected() {
        Transaction honest = tx(0, 10, 2);
        Transaction forged = tx(0, 50_000, 2);

        String honestCmd = TransactionCommandCodec.encode(honest);
        String forgedCmd = TransactionCommandCodec.encode(forged);

        Map<Long, String> verified = new HashMap<>();
        verified.put(101L, honestCmd);

        Block malicious = new Block(
            0,
            new TxBatchPayload(List.of(new TxBatchPayload.TxItem(101L, forgedCmd))).toBytes()
        );

        assertFalse(isVerifiedClientBatch(verified, malicious));
    }

    @Test
    void byzantineLeader_unknownRequestId_isRejected() {
        String cmd = TransactionCommandCodec.encode(tx(0, 10, 2));
        Block malicious = new Block(
            0,
            new TxBatchPayload(List.of(new TxBatchPayload.TxItem(999L, cmd))).toBytes()
        );

        assertFalse(isVerifiedClientBatch(Map.of(), malicious));
    }

    @Test
    void byzantineLeader_malformedTransactionEncoding_isRejected() {
        Map<Long, String> verified = new HashMap<>();
        verified.put(102L, "not-a-valid-transaction-command");

        Block malicious = new Block(
            0,
            new TxBatchPayload(List.of(new TxBatchPayload.TxItem(102L, "not-a-valid-transaction-command"))).toBytes()
        );

        assertFalse(isVerifiedClientBatch(verified, malicious));
    }

    private static Transaction tx(long nonce, long value, long gasPrice) {
        return new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            nonce,
            value,
            gasPrice,
            21_000,
            null
        );
    }
}
