package depchain.demo;

import depchain.blockchain.Transaction;
import depchain.blockchain.BlockchainMember;
import depchain.client.DepChainClient;
import depchain.config.NodeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the key-file flow works: write keys, load in 4 members, client appends.
 * Uses a random port base to avoid conflict with the fixed multi-JVM ports.
 */
class MultiProcessConfigIntegrationTest {

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void keyFileThenFourMembersAndClientSucceed(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("test-multijvm.keys");
        MultiProcessConfig.writeKeysToFile(keyFile);

        int base = 36000 + (int) (Math.random() * 1000);
        int consensusBase = base;
        int clientBase = base + 100;
        int clientListen = base + 200;

        List<BlockchainMember> members = new ArrayList<>();
        for (int i = 0; i < MultiProcessConfig.N; i++) {
            MultiProcessConfig.MemberConfig config = MultiProcessConfig.loadMemberFromFile(keyFile, i, consensusBase, clientBase);
            BlockchainMember m = new BlockchainMember(
                    i, config.membership, consensusBase + i, clientBase + i,
                    config, MultiProcessConfig.VIEW_TIMEOUT_MS);
            members.add(m);
        }

        Thread.sleep(800);
        List<NodeAddress> targets = MultiProcessConfig.getClientTargets(clientBase);
        DepChainClient client = new DepChainClient(targets, clientListen, 15000L, 5);

        try {
            Transaction tx = new Transaction(
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                0,
                7,
                1,
                21_000,
                null
            );
            int idx = client.appendTransaction(tx);
            assertTrue(idx >= 0, "append should succeed with key file; got " + idx);
            assertTrue(members.get(0).getLedger().getBlocks().size() >= 1);
            assertEquals(1, members.get(0).getLedger().getBlocks().get(0).getTransactions().size());
        } finally {
            client.close();
            for (BlockchainMember m : members) m.close();
        }
    }
}
