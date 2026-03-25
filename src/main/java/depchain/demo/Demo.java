package depchain.demo;

import depchain.blockchain.BlockchainMember;
import depchain.blockchain.Transaction;
import depchain.blockchain.TransactionCommandCodec;
import depchain.client.DepChainClient;
import depchain.config.Membership;
import depchain.config.NodeAddress;
import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;

import java.security.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo: 4 members with threshold signatures, 1 client appends strings.
 */
public class Demo {
    private static final int N = 4;
    private static final int BASE_CONSENSUS = 30000;
    private static final int BASE_CLIENT = 30100;
    private static final int CLIENT_LISTEN = 30200;

    public static void main(String[] args) throws Exception {
        KeyPairGenerator aplGen = KeyPairGenerator.getInstance("RSA");
        aplGen.initialize(2048);
        Dealer dealer = new Dealer(512);
        int quorum = 2 * (N - 1) / 3 + 1;
        dealer.generateKeys(quorum, N);
        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] shares = dealer.getShares();

        List<Integer> ids = new ArrayList<>();
        Map<Integer, NodeAddress> addrs = new ConcurrentHashMap<>();
        Map<Integer, PublicKey> pubs = new ConcurrentHashMap<>();
        Map<Integer, KeyPair> aplKeys = new ConcurrentHashMap<>();
        for (int i = 0; i < N; i++) {
            ids.add(i);
            addrs.put(i, new NodeAddress("127.0.0.1", BASE_CONSENSUS + i));
            KeyPair kp = aplGen.generateKeyPair();
            aplKeys.put(i, kp);
            pubs.put(i, kp.getPublic());
        }
        Membership membership = new Membership(ids, addrs, pubs);

        List<BlockchainMember> members = new ArrayList<>();
        List<NodeAddress> clientTargets = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            MultiProcessConfig.MemberConfig config = new MultiProcessConfig.MemberConfig(
                    membership, aplKeys.get(i).getPrivate(), shares[i], groupKey);
            BlockchainMember m = new BlockchainMember(i, membership, BASE_CONSENSUS + i, BASE_CLIENT + i, config, 2000L);
            members.add(m);
            clientTargets.add(new NodeAddress("127.0.0.1", BASE_CLIENT + i));
        }

        Thread.sleep(1500);
        DepChainClient client = new DepChainClient(clientTargets, CLIENT_LISTEN, 10000L, 5);

        System.out.println("DepChain demo: 4 members (threshold sig), 1 client. Appending 3 RSA-signed txs (JSON)...");
        for (int k = 0; k < 3; k++) {
            Transaction tx = new Transaction(
                    "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                    "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                    k,
                    10,
                    1,
                    21_000,
                    null);
            String cmd = TransactionCommandCodec.encode(tx);
            int idx = client.append(cmd);
            System.out.println("  append(tx nonce=" + k + ") -> index " + idx);
        }

        Thread.sleep(500);
        System.out.println("Blockchain content at member 0: " + members.get(0).getBlockchain().getLog());
        client.close();
        for (BlockchainMember m : members) m.close();
        System.out.println("Demo finished.");
    }
}
