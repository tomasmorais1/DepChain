package depchain.demo;

import depchain.blockchain.Transaction;
import depchain.blockchain.BlockchainMember;
import depchain.client.DepChainClient;
import depchain.config.NodeAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
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
        Credentials eth = Credentials.create(
                "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        DepChainClient client = new DepChainClient(
                targets, clientListen, new InetSocketAddress("127.0.0.1", clientListen), null, eth, 15000L, 5);

        try {
            Transaction tx = new Transaction(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
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
