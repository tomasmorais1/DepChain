package depchain.consensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import depchain.blockchain.BlockchainService;
import depchain.config.Membership;
import depchain.config.NodeAddress;
import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replay tests: leader or replica messages are replayed (duplicated).
 * Replicas must ignore replayed messages and decide exactly once.
 */
class ReplayConsensusTest {

    static ConsensusTestHarness harness(int n, int basePort) throws Exception {
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
        return new ConsensusTestHarness(n, basePort, membership, shares, groupKey, keys);
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void replayLeaderPrepare_allDecideOnce() throws Exception {
        int n = 4;
        int basePort = 27000 + (int) (Math.random() * 1000);
        ConsensusTestHarness h = harness(n, basePort);
        for (int i = 0; i < n; i++) {
            ConsensusNetwork net = new APLConsensusNetwork(i, h.apls.get(i), h.membership);
            net = new ReplayConsensusNetwork(net, true, false);
            h.addReplica(net, i);
        }
        try {
            Block block = new Block(0, "replay-leader".getBytes(StandardCharsets.UTF_8));
            h.replicas.get(0).propose(block);
            h.waitUntilAllDecided(1, 450);
            for (BlockchainService c : h.blockchains) {
                assertEquals(1, c.size());
                assertEquals("replay-leader", c.getLog().get(0));
            }
        } finally {
            h.close();
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void replayReplicaVote_allDecideOnce() throws Exception {
        int n = 4;
        int basePort = 27100 + (int) (Math.random() * 1000);
        ConsensusTestHarness h = harness(n, basePort);
        for (int i = 0; i < n; i++) {
            ConsensusNetwork net = new APLConsensusNetwork(i, h.apls.get(i), h.membership);
            net = new ReplayConsensusNetwork(net, false, true);
            h.addReplica(net, i);
        }
        try {
            Block block = new Block(0, "replay-replica".getBytes(StandardCharsets.UTF_8));
            h.replicas.get(0).propose(block);
            h.waitUntilAllDecided(1, 450);
            for (BlockchainService c : h.blockchains) {
                assertEquals(1, c.size());
                assertEquals("replay-replica", c.getLog().get(0));
            }
        } finally {
            h.close();
        }
    }
}
