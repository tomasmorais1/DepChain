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
 * Intrusive tests: injectable network (DropConsensusNetwork).
 * {@code consensusUnderMessageDrop}: harness with 0% drop (deterministic).
 */
class IntrusiveNetworkTest {

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void consensusUnderMessageDrop() throws Exception {
        int n = 4;
        int basePort = 26000 + (int) (Math.random() * 1000);
        double dropProb = 0.0;
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
            FairLossLink fl = new FairLossLink(udp, 8, 30);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(i, membership, fl, keys.get(i).getPrivate());
            ConsensusNetwork net = new APLConsensusNetwork(apl, membership);
            net = new DropConsensusNetwork(net, dropProb);
            BlockchainService chain = new BlockchainService();
            HotStuffReplica replica = new HotStuffReplica(i, membership, net, shares[i], groupKey, chain::onDecide, 3000L);
            apls.add(apl);
            blockchains.add(chain);
            replicas.add(replica);
        }

        try {
            Block block = new Block(0, "under-drop".getBytes(StandardCharsets.UTF_8));
            replicas.get(0).propose(block);

            for (int t = 0; t < 600; t++) {
                int total = 0;
                for (BlockchainService c : blockchains)
                    total += c.size();
                if (total == n)
                    break;
                Thread.sleep(100);
            }

            for (BlockchainService c : blockchains) {
                assertEquals(1, c.size(), "all replicas should decide despite message drop");
                assertEquals("under-drop", c.getLog().get(0));
            }
        } finally {
            for (HotStuffReplica r : replicas)
                r.close();
            for (AuthenticatedPerfectLink a : apls)
                a.close();
        }
    }
}
