package depchain.client;

import depchain.blockchain.Transaction;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.junit.jupiter.api.Timeout;
import depchain.blockchain.BlockchainMember;
import depchain.config.Membership;
import depchain.config.NodeAddress;
import depchain.demo.MultiProcessConfig;
import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client broadcasts append to all members; leader proposes; all decide and
 * respond.
 */
class ClientIntegrationTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void clientAppendReachesBlockchain() throws Exception {
        int n = 4;
        int basePort = 24000 + (int) (Math.random() * 1000);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        int quorum = 2 * (n - 1) / 3 + 1;
        Dealer dealer = new Dealer(512);
        dealer.generateKeys(quorum, n);
        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] shares = dealer.getShares();

        List<Integer> ids = new ArrayList<>();
        Map<Integer, NodeAddress> addrs = new ConcurrentHashMap<>();
        Map<Integer, java.security.PublicKey> pubs = new ConcurrentHashMap<>();
        Map<Integer, KeyPair> keys = new ConcurrentHashMap<>();
        for (int i = 0; i < n; i++) {
            ids.add(i);
            addrs.put(i, new NodeAddress("127.0.0.1", basePort + i));
            KeyPair kp = gen.generateKeyPair();
            keys.put(i, kp);
            pubs.put(i, kp.getPublic());
        }
        Membership membership = new Membership(ids, addrs, pubs);

        List<BlockchainMember> members = new ArrayList<>();
        List<NodeAddress> clientTargets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int consensusPort = basePort + i;
            int clientPort = basePort + n + 100 + i;
            clientTargets.add(new NodeAddress("127.0.0.1", clientPort));
            MultiProcessConfig.MemberConfig config = new MultiProcessConfig.MemberConfig(
                    membership, keys.get(i).getPrivate(), shares[i], groupKey);
            BlockchainMember m = new BlockchainMember(i, membership, consensusPort, clientPort, config, 3000L);
            members.add(m);
        }

        int clientListenPort = basePort + 500;
        Credentials eth = Credentials.create(
                "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        DepChainClient client = new DepChainClient(
                clientTargets,
                clientListenPort,
                new InetSocketAddress("127.0.0.1", clientListenPort),
                null,
                eth,
                8000L,
                5);

        try {
            Transaction tx = new Transaction(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                0,
                25,
                1,
                21_000,
                null
            );
            int idx = client.appendTransaction(tx);
            assertTrue(idx >= 0, "append should succeed");

            for (int t = 0; t < 50; t++) {
                int size = members.get(0).getLedger().getBlocks().size();
                if (size >= 1)
                    break;
                Thread.sleep(100);
            }
            assertEquals(1, members.get(0).getLedger().getBlocks().size());
            assertEquals(
                1,
                members.get(0).getLedger().getBlocks().get(0).getTransactions().size()
            );
        } finally {
            client.close();
            for (BlockchainMember m : members)
                m.close();
        }
    }
}
