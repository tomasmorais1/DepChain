package depchain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import depchain.blockchain.BlockchainMember;
import depchain.blockchain.Transaction;
import depchain.config.Membership;
import depchain.config.NodeAddress;
import depchain.demo.MultiProcessConfig;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.web3j.crypto.Credentials;
import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;

/**
 * End-to-end: commit a native transfer, then query balances by asking replicas (no chain download).
 */
class ClientBalanceReadAfterWriteIntegrationTest {

    @Test
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void queryDepCoinReflectsCommittedTransfer() throws Exception {
        int n = 4;
        int basePort = 34500 + (int) (Math.random() * 1000);
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
            MultiProcessConfig.MemberConfig config =
                new MultiProcessConfig.MemberConfig(membership, keys.get(i).getPrivate(), shares[i], groupKey);
            members.add(new BlockchainMember(i, membership, consensusPort, clientPort, config, 3000L));
        }

        int clientListenPort = basePort + 500;
        Credentials eth =
            Credentials.create(
                "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
            );
        DepChainClient client =
            new DepChainClient(
                clientTargets,
                clientListenPort,
                new InetSocketAddress("127.0.0.1", clientListenPort),
                null,
                eth,
                8000L,
                5
            );

        String alice = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String bob = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
        try {
            Transaction tx =
                new Transaction(
                    alice,
                    bob,
                    0,
                    7,
                    1,
                    21_000,
                    null
                );
            int idx = client.appendTransaction(tx);
            assertTrue(idx >= 0);

            // Wait until at least 1 decided block shows up on a member.
            for (int t = 0; t < 60; t++) {
                if (!members.get(0).getLedger().getBlocks().isEmpty()) break;
                Thread.sleep(100);
            }

            long aliceBal = client.queryDepCoinBalance(alice, idx);
            long bobBal = client.queryDepCoinBalance(bob, idx);

            // Initial 10_000_000. Fee for native transfer is 21_000 * gasPrice(1) = 21_000, plus value 7.
            assertEquals(10_000_000L - 21_000L - 7L, aliceBal);
            assertEquals(10_000_000L + 7L, bobBal);
        } finally {
            client.close();
            for (BlockchainMember m : members) m.close();
        }
    }
}

