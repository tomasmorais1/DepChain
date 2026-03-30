package depchain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import depchain.blockchain.BlockchainMember;
import depchain.blockchain.TransactionExecutor;
import depchain.blockchain.evm.IstCoinBytecode;
import depchain.config.Membership;
import depchain.config.NodeAddress;
import depchain.demo.MultiProcessConfig;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.web3j.crypto.Credentials;
import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;

/**
 * End-to-end: one target port replies with wrong returnData; client still succeeds by waiting for f+1 identical replies.
 */
class ClientQueryByzantineReplyIntegrationTest {

    @Test
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void istBalanceQuery_toleratesOneByzantineWrongReply() throws Exception {
        int n = 4;
        int basePort = 33000 + (int) (Math.random() * 1000);

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

        // Start only 3 honest members; the 4th "member" is a fake UDP responder that sends wrong query replies.
        List<BlockchainMember> members = new ArrayList<>();
        List<NodeAddress> clientTargets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int clientPort = basePort + n + 100 + i;
            clientTargets.add(new NodeAddress("127.0.0.1", clientPort));
            if (i == n - 1) {
                continue; // fake byzantine responder will bind this port
            }
            int consensusPort = basePort + i;
            MultiProcessConfig.MemberConfig config =
                new MultiProcessConfig.MemberConfig(membership, keys.get(i).getPrivate(), shares[i], groupKey);
            members.add(new BlockchainMember(i, membership, consensusPort, clientPort, config, 3000L));
        }

        int byzClientPort = basePort + n + 100 + (n - 1);
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread byz =
            new Thread(
                () -> {
                    try (DatagramSocket sock = new DatagramSocket(byzClientPort)) {
                        byte[] buf = new byte[ClientProtocol.MAX_REQUEST_WIRE];
                        while (!stop.get()) {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            sock.receive(p);
                            byte[] copy = new byte[p.getLength()];
                            System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                            ClientProtocol.Request req = ClientProtocol.parseRequest(copy);
                            if (req == null || req.getProtocolKind() != ClientProtocol.TYPE_QUERY) {
                                continue;
                            }
                            // Reply with wrong returnData (zero uint256) but valid wire format.
                            byte[] wrong = new byte[32];
                            byte[] resp =
                                ClientProtocol.encodeQueryResponse(
                                    req.getRequestId(),
                                    true,
                                    0,
                                    "",
                                    wrong
                                );
                            DatagramPacket out = new DatagramPacket(resp, resp.length, p.getAddress(), p.getPort());
                            sock.send(out);
                        }
                    } catch (Exception ignored) {
                        // test teardown
                    }
                },
                "byz-query-replier"
            );
        byz.setDaemon(true);
        byz.start();

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

        String deployer = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        try {
            String istContract = TransactionExecutor.deriveContractAddress(deployer, 0L);
            BigInteger istBal = client.queryIstBalanceOf(istContract, deployer);
            assertEquals(BigInteger.valueOf(IstCoinBytecode.TOTAL_SUPPLY_UNITS), istBal);
        } finally {
            client.close();
            stop.set(true);
            byz.interrupt();
            for (BlockchainMember m : members) m.close();
        }
    }
}

