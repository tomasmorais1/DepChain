package depchain.consensus;

import depchain.blockchain.BlockchainService;
import depchain.config.Membership;
import depchain.links.AuthenticatedPerfectLink;
import depchain.transport.FairLossLink;
import depchain.transport.UdpTransport;
import threshsig.GroupKey;
import threshsig.KeyShare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared harness to build n replicas with UDP, APL, and optional network wrappers.
 */
final class ConsensusTestHarness {
    final int n;
    final int basePort;
    final Membership membership;
    final KeyShare[] shares;
    final GroupKey groupKey;
    final List<AuthenticatedPerfectLink> apls = new ArrayList<>();
    final List<BlockchainService> blockchains = new ArrayList<>();
    final List<HotStuffReplica> replicas = new ArrayList<>();

    ConsensusTestHarness(int n, int basePort, Membership membership, KeyShare[] shares, GroupKey groupKey,
            Map<Integer, java.security.KeyPair> keys) throws Exception {
        this.n = n;
        this.basePort = basePort;
        this.membership = membership;
        this.shares = shares;
        this.groupKey = groupKey;
        for (int i = 0; i < n; i++) {
            UdpTransport udp = new UdpTransport(basePort + i, 8192);
            FairLossLink fl = new FairLossLink(udp, 5, 40);
            AuthenticatedPerfectLink apl = new AuthenticatedPerfectLink(i, membership, fl, keys.get(i).getPrivate());
            apls.add(apl);
            blockchains.add(new BlockchainService());
        }
    }

    void addReplica(ConsensusNetwork network, int replicaIndex) {
        HotStuffReplica replica = new HotStuffReplica(replicaIndex, membership, network, shares[replicaIndex],
                groupKey, blockchains.get(replicaIndex)::onDecide);
        replicas.add(replica);
    }

    void waitUntilAllDecided(int expectedSize, int maxWaitMs) throws InterruptedException {
        for (int t = 0; t < maxWaitMs; t += 50) {
            int total = 0;
            for (BlockchainService c : blockchains)
                total += c.size();
            if (total == n * expectedSize)
                break;
            Thread.sleep(50);
        }
    }

    void close() {
        for (HotStuffReplica r : replicas)
            r.close();
        for (AuthenticatedPerfectLink a : apls)
            a.close();
    }
}
