package depchain.consensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import depchain.blockchain.BlockchainService;
import depchain.config.Membership;
import depchain.config.NodeAddress;
import depchain.links.AuthenticatedPerfectLink;
import depchain.transport.FairLossLink;
import depchain.transport.UdpTransport;
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
 * Tests with 1 or 2 replicas failing.
 * - One replica failing (votes dropped): replica does not send votes but sends NEW_VIEW; quorum of 3 others decide, all 4 receive DECIDE.
 * - Two replicas failing (silent): 2 replicas send nothing; no quorum (need 3), so no decision.
 */
class ReplicaFailingTest {

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void oneReplicaFails_otherThreeDecide() throws Exception {
        int n = 4;
        int basePort = 27200 + (int) (Math.random() * 1000);
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
        List<AuthenticatedPerfectLink> apls = new ArrayList<>();
        List<BlockchainService> blockchains = new ArrayList<>();
        List<HotStuffReplica> replicas = new ArrayList<>();
        final int failingReplica = 3;
        for (int i = 0; i < n; i++) {
            UdpTransport udp = new UdpTransport(basePort + i, 8192);
            FairLossLink fl = new FairLossLink(udp, 5, 40);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(i, membership, fl, keys.get(i).getPrivate());
            ConsensusNetwork net = new APLConsensusNetwork(i, apl, membership);
            if (i == failingReplica)
                net = new VoteDroppingConsensusNetwork(net);
            BlockchainService chain = new BlockchainService();
            HotStuffReplica replica = new HotStuffReplica(i, membership, net, shares[i], groupKey, chain::onDecide);
            apls.add(apl);
            blockchains.add(chain);
            replicas.add(replica);
        }
        try {
            Block block = new Block(0, "one-fail".getBytes(StandardCharsets.UTF_8));
            replicas.get(0).propose(block);
            for (int t = 0; t < 450; t++) {
                int total = 0;
                for (BlockchainService c : blockchains)
                    total += c.size();
                if (total == n)
                    break;
                Thread.sleep(100);
            }
            for (BlockchainService c : blockchains) {
                assertEquals(1, c.size());
                assertEquals("one-fail", c.getLog().get(0));
            }
        } finally {
            for (HotStuffReplica r : replicas)
                r.close();
            for (AuthenticatedPerfectLink a : apls)
                a.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void twoReplicasFail_noQuorum() throws Exception {
        int n = 4;
        int basePort = 27300 + (int) (Math.random() * 1000);
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
        List<AuthenticatedPerfectLink> apls = new ArrayList<>();
        List<BlockchainService> blockchains = new ArrayList<>();
        List<HotStuffReplica> replicas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UdpTransport udp = new UdpTransport(basePort + i, 8192);
            FairLossLink fl = new FairLossLink(udp, 5, 40);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(i, membership, fl, keys.get(i).getPrivate());
            ConsensusNetwork net = new APLConsensusNetwork(i, apl, membership);
            if (i == 2 || i == 3)
                net = new SilentConsensusNetwork(net);
            BlockchainService chain = new BlockchainService();
            HotStuffReplica replica = new HotStuffReplica(i, membership, net, shares[i], groupKey, chain::onDecide, 2000L);
            apls.add(apl);
            blockchains.add(chain);
            replicas.add(replica);
        }
        try {
            Block block = new Block(0, "two-fail".getBytes(StandardCharsets.UTF_8));
            replicas.get(0).propose(block);
            Thread.sleep(18_000);
            int totalDecided = 0;
            for (BlockchainService c : blockchains)
                totalDecided += c.size();
            assertTrue(totalDecided < 4, "with only 2 correct replicas there is no quorum (3); no one should decide");
        } finally {
            for (HotStuffReplica r : replicas)
                r.close();
            for (AuthenticatedPerfectLink a : apls)
                a.close();
        }
    }
}
