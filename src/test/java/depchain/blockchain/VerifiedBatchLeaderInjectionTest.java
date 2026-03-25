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
 * Stage 2 Step 5: a Byzantine leader could try to propose a batch containing a transaction string
 * that was never verified on this replica. {@code BlockchainMember} rejects such blocks via
 * {@code HotStuffReplica.BlockValidator}; this test mirrors that predicate without starting UDP.
 */
class VerifiedBatchLeaderInjectionTest {

    /** Same logic as {@code BlockchainMember#isVerifiedClientBlock}. */
    static boolean isVerifiedClientBatch(Map<Long, String> verifiedClientRequests, Block block) {
        TxBatchPayload batch = TxBatchPayload.fromBytes(block.getPayload());
        if (batch == null || batch.getItems().isEmpty()) {
            return true;
        }
        for (TxBatchPayload.TxItem item : batch.getItems()) {
            if (
                !Objects.equals(verifiedClientRequests.get(item.requestId()), item.command()) ||
                TransactionCommandCodec.decode(item.command()) == null
            ) {
                return false;
            }
        }
        return true;
    }

    @Test
    void emptyBatchAccepted() {
        assertTrue(isVerifiedClientBatch(Map.of(), new Block(0, new TxBatchPayload(List.of()).toBytes())));
    }

    @Test
    void honestBatchAcceptedWhenCommandsMatchVerifiedMap() {
        Transaction tx = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            1,
            1,
            21_000,
            null
        );
        String cmd = TransactionCommandCodec.encode(tx);
        Map<Long, String> verified = new HashMap<>();
        verified.put(7L, cmd);
        Block block = new Block(
            0,
            new TxBatchPayload(List.of(new TxBatchPayload.TxItem(7L, cmd))).toBytes()
        );
        assertTrue(isVerifiedClientBatch(verified, block));
    }

    @Test
    void byzantineLeader_injectedCommandWithoutVerification_rejected() {
        Transaction honest = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            1,
            1,
            21_000,
            null
        );
        Transaction forged = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            9_999_999,
            1,
            21_000,
            null
        );
        String verifiedCmd = TransactionCommandCodec.encode(honest);
        Map<Long, String> verified = new HashMap<>();
        verified.put(1L, verifiedCmd);
        Block malicious = new Block(
            0,
            new TxBatchPayload(List.of(new TxBatchPayload.TxItem(1L, TransactionCommandCodec.encode(forged)))).toBytes()
        );
        assertFalse(isVerifiedClientBatch(verified, malicious));
    }

    @Test
    void unknownRequestId_rejected() {
        Transaction tx = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            1,
            1,
            21_000,
            null
        );
        String cmd = TransactionCommandCodec.encode(tx);
        Block block = new Block(
            0,
            new TxBatchPayload(List.of(new TxBatchPayload.TxItem(99L, cmd))).toBytes()
        );
        assertFalse(isVerifiedClientBatch(Map.of(), block));
    }
}
