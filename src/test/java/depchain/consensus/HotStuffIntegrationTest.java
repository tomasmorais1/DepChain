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
 * Integration test: 4 replicas, leader proposes blocks, all decide and append
 * to blockchain.
 */
class HotStuffIntegrationTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void fourReplicasDecideOneBlock() throws Exception {
        int n = 4;
        int basePort = 17000 + (int) (Math.random() * 2000);
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

        List<UdpTransport> udps = new ArrayList<>();
        List<FairLossLink> fairLosses = new ArrayList<>();
        List<AuthenticatedPerfectLink> apls = new ArrayList<>();
        List<ConsensusNetwork> networks = new ArrayList<>();
        List<BlockchainService> blockchains = new ArrayList<>();
        List<HotStuffReplica> replicas = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            UdpTransport udp = new UdpTransport(basePort + i, 8192);
            FairLossLink fl = new FairLossLink(udp, 5, 40);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(i, membership, fl, keys.get(i).getPrivate());
            APLConsensusNetwork net = new APLConsensusNetwork(i, apl, membership);
            BlockchainService chain = new BlockchainService();
            HotStuffReplica replica = new HotStuffReplica(i, membership, net, shares[i], groupKey, chain::onDecide);
            udps.add(udp);
            fairLosses.add(fl);
            apls.add(apl);
            networks.add(net);
            blockchains.add(chain);
            replicas.add(replica);
        }

        try {
            Block block = new Block(0, "hello".getBytes(StandardCharsets.UTF_8));
            replicas.get(0).propose(block);

            for (int t = 0; t < 300; t++) {
                int total = 0;
                for (BlockchainService c : blockchains)
                    total += c.size();
                if (total == n)
                    break;
                Thread.sleep(100);
            }

            for (BlockchainService c : blockchains) {
                assertEquals(1, c.size());
                assertEquals("hello", c.getLog().get(0));
            }
        } finally {
            for (HotStuffReplica r : replicas)
                r.close();
            for (AuthenticatedPerfectLink a : apls)
                a.close();
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void fourReplicasDecideMultipleBlocks() throws Exception {
        int n = 4;
        int basePort = 18000 + (int) (Math.random() * 2000);
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
            APLConsensusNetwork net = new APLConsensusNetwork(i, apl, membership);
            BlockchainService chain = new BlockchainService();
            HotStuffReplica replica = new HotStuffReplica(i, membership, net, shares[i], groupKey, chain::onDecide);
            apls.add(apl);
            blockchains.add(chain);
            replicas.add(replica);
        }

        try {
            String payload0 = "msg-0";
            replicas.get(0).propose(new Block(0, payload0.getBytes(StandardCharsets.UTF_8)));
            while (blockchains.stream().mapToInt(BlockchainService::size).min().orElse(0) < 1)
                Thread.sleep(50);

            String payload1 = "msg-1";
            replicas.get(1).propose(new Block(1, payload1.getBytes(StandardCharsets.UTF_8)));
            while (blockchains.stream().mapToInt(BlockchainService::size).min().orElse(0) < 2)
                Thread.sleep(50);

            String payload2 = "msg-2";
            replicas.get(2).propose(new Block(2, payload2.getBytes(StandardCharsets.UTF_8)));
            for (int t = 0; t < 300; t++) {
                if (blockchains.stream().mapToInt(BlockchainService::size).min().orElse(0) >= 3)
                    break;
                Thread.sleep(100);
            }

            for (BlockchainService c : blockchains) {
                assertEquals(3, c.size());
                assertEquals("msg-0", c.getLog().get(0));
                assertEquals("msg-1", c.getLog().get(1));
                assertEquals("msg-2", c.getLog().get(2));
            }
        } finally {
            for (HotStuffReplica r : replicas)
                r.close();
            for (AuthenticatedPerfectLink a : apls)
                a.close();
        }
    }

    /**
     * Leader (replica 0) never proposes; after timeout replicas move to view 1;
     * replica 1 proposes and all decide.
     */
    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void viewChangeAfterLeaderTimeout() throws Exception {
        int n = 4;
        int basePort = 19000 + (int) (Math.random() * 2000);
        long viewTimeoutMs = 800;
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
            APLConsensusNetwork net = new APLConsensusNetwork(i, apl, membership);
            BlockchainService chain = new BlockchainService();
            HotStuffReplica replica = new HotStuffReplica(i, membership, net, shares[i], groupKey, chain::onDecide, viewTimeoutMs);
            apls.add(apl);
            blockchains.add(chain);
            replicas.add(replica);
        }

        try {
            Thread.sleep(viewTimeoutMs + 500);
            Block block = new Block(1, "after-view-change".getBytes(StandardCharsets.UTF_8));
            replicas.get(1).propose(block);

            for (int t = 0; t < 200; t++) {
                int minSize = blockchains.stream().mapToInt(BlockchainService::size).min().orElse(0);
                if (minSize >= 1)
                    break;
                Thread.sleep(100);
            }

            for (BlockchainService c : blockchains) {
                assertEquals(1, c.size());
                assertEquals("after-view-change", c.getLog().get(0));
            }
        } finally {
            for (HotStuffReplica r : replicas)
                r.close();
            for (AuthenticatedPerfectLink a : apls)
                a.close();
        }
    }
}
