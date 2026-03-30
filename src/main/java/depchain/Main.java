package depchain;

import depchain.blockchain.BlockchainMember;
import depchain.blockchain.Transaction;
import depchain.client.DepChainClient;
import depchain.config.NodeAddress;
import depchain.demo.MultiProcessConfig;
import org.web3j.crypto.Credentials;

import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.List;


public class Main {
    /** Hardhat default account #0 (matches genesis first account). */
    private static final String DEMO_ETH_PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    public static void main(String[] args) {
        try {
            if (args.length >= 1 && "demo".equalsIgnoreCase(args[0])) {
                depchain.demo.Demo.main(args);
                return;
            }
            if (args.length >= 2 && "member".equalsIgnoreCase(args[0])) {
                runMember(args[1]);
                return;
            }
            if (args.length >= 1 && "client".equalsIgnoreCase(args[0])) {
                runClient();
                return;
            }
            if (args.length >= 1 && "genconfig".equalsIgnoreCase(args[0])) {
                runGenConfig();
                return;
            }
            if (args.length >= 1 && "interactive".equalsIgnoreCase(args[0])) {
                depchain.client.InteractiveCli.main(args);
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        printUsage();
    }

    private static void runMember(String idStr) throws Exception {
        int id = Integer.parseInt(idStr);
        if (id < 0 || id >= MultiProcessConfig.N) {
            System.err.println("Member id must be 0.." + (MultiProcessConfig.N - 1));
            System.exit(1);
        }
        java.nio.file.Path keyFile = Paths.get(MultiProcessConfig.DEFAULT_KEY_FILE);
        if (!java.nio.file.Files.exists(keyFile)) {
            System.err.println("Key file not found: " + keyFile.toAbsolutePath());
            System.err.println("Run once:  java -cp target/classes depchain.Main genconfig");
            System.exit(1);
        }
        MultiProcessConfig.MemberConfig config = MultiProcessConfig.loadMemberFromFile(keyFile, id);
        int consensusPort = MultiProcessConfig.BASE_CONSENSUS + id;
        int clientPort = MultiProcessConfig.BASE_CLIENT + id;
        BlockchainMember member = new BlockchainMember(
                id, config.membership, consensusPort, clientPort,
                config, MultiProcessConfig.VIEW_TIMEOUT_MS);
        System.out.println("Member " + id + " running (consensus port " + consensusPort + ", client port " + clientPort + "). Press Enter to stop.");
        try {
            if (System.console() != null) {
                System.in.read();
            } else {
                Thread.currentThread().join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            member.close();
        }
    }

    private static void runClient() throws Exception {
        List<NodeAddress> targets = MultiProcessConfig.getClientTargets();
        System.out.println("DepChain client: waiting 4s for members to be ready...");
        Thread.sleep(4000);
        InetSocketAddress bindAddr = new InetSocketAddress("127.0.0.1", MultiProcessConfig.CLIENT_LISTEN);
        Credentials eth = Credentials.create(DEMO_ETH_PRIVATE_KEY);
        DepChainClient client = new DepChainClient(
                targets, MultiProcessConfig.CLIENT_LISTEN, bindAddr, null, eth, 15000L, 5);
        System.out.println("Submitting 3 transactions to members at " + targets + " ...");
        boolean anyFail = false;
        for (int k = 0; k < 3; k++) {
            Transaction tx = new Transaction(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                k,
                10,
                1,
                21_000,
                null
            );
            int idx = client.appendTransaction(tx);
            System.out.println("  append(tx nonce=" + k + ") -> index " + idx);
            if (idx < 0) anyFail = true;
        }
        client.close();
        if (anyFail) {
            System.err.println("Some appends failed (index -1). Ensure all 4 members are running:");
            System.err.println("  java -cp target/classes depchain.Main member 0");
            System.err.println("  java -cp target/classes depchain.Main member 1");
            System.err.println("  java -cp target/classes depchain.Main member 2");
            System.err.println("  java -cp target/classes depchain.Main member 3");
            System.err.println("Wait until all four print \"Member X running\", then run the client.");
        } else {
            System.out.println("Client finished.");
        }
    }

    private static void runGenConfig() throws Exception {
        java.nio.file.Path path = Paths.get(MultiProcessConfig.DEFAULT_KEY_FILE);
        MultiProcessConfig.writeKeysToFile(path);
        System.out.println("Config written to " + path.toAbsolutePath());
        System.out.println("Now run in 4 separate terminals:");
        System.out.println("  java -cp target/classes depchain.Main member 0");
        System.out.println("  java -cp target/classes depchain.Main member 1");
        System.out.println("  java -cp target/classes depchain.Main member 2");
        System.out.println("  java -cp target/classes depchain.Main member 3");
        System.out.println("Then in a 5th terminal:");
        System.out.println("  java -cp target/classes depchain.Main client");
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java depchain.Main demo             -- 4 members + 1 client in one JVM");
        System.err.println("  java depchain.Main genconfig        -- generate key file for multi-JVM (run once)");
        System.err.println("  java depchain.Main member <0|1|2|3> -- run one member (after genconfig)");
        System.err.println("  java depchain.Main client           -- run client only (5th terminal)");
        System.err.println("  java depchain.Main interactive      -- IST/DepCoin REPL (after members up)");
        System.err.println("Or: mvn exec:java -Dexec.mainClass=depchain.demo.Demo");
    }
}
