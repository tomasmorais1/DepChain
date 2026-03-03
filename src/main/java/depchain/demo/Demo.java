package depchain.demo;

import depchain.blockchain.BlockchainMember;
import depchain.client.DepChainClient;
import depchain.config.Membership;
import depchain.config.NodeAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo: start 4 blockchain members and 1 client; client appends a few strings.
 * Uses fixed ports 30000-30003 (consensus), 30100-30103 (client), 30200 (client listen).
 */
public class Demo {
    private static final int N = 4;
    private static final int BASE_CONSENSUS = 30000;
    private static final int BASE_CLIENT = 30100;
    private static final int CLIENT_LISTEN = 30200;

    public static void main(String[] args) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);

        List<Integer> ids = new ArrayList<>();
        Map<Integer, NodeAddress> addrs = new ConcurrentHashMap<>();
        Map<Integer, PublicKey> pubs = new ConcurrentHashMap<>();
        Map<Integer, KeyPair> keys = new ConcurrentHashMap<>();
        for (int i = 0; i < N; i++) {
            ids.add(i);
            addrs.put(i, new NodeAddress("127.0.0.1", BASE_CONSENSUS + i));
            KeyPair kp = gen.generateKeyPair();
            keys.put(i, kp);
            pubs.put(i, kp.getPublic());
        }
        Membership membership = new Membership(ids, addrs, pubs);

        List<BlockchainMember> members = new ArrayList<>();
        List<NodeAddress> clientTargets = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            BlockchainMember m = new BlockchainMember(i, membership, BASE_CONSENSUS + i, BASE_CLIENT + i, keys.get(i).getPrivate(), 2000L);
            members.add(m);
            clientTargets.add(new NodeAddress("127.0.0.1", BASE_CLIENT + i));
        }

        Thread.sleep(1500);
        DepChainClient client = new DepChainClient(clientTargets, CLIENT_LISTEN, 10000L, 5);

        System.out.println("DepChain demo: 4 members, 1 client. Appending 3 strings...");
        for (int k = 0; k < 3; k++) {
            String s = "demo-string-" + (k + 1);
            int idx = client.append(s);
            System.out.println("  append(\"" + s + "\") -> index " + idx);
        }

        Thread.sleep(500);
        System.out.println("Blockchain content at member 0: " + members.get(0).getBlockchain().getLog());
        client.close();
        for (BlockchainMember m : members) m.close();
        System.out.println("Demo finished.");
    }
}
