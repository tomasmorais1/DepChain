package depchain.client;

import depchain.blockchain.Genesis;
import depchain.blockchain.GenesisLoader;
import depchain.blockchain.Transaction;
import depchain.blockchain.TransactionExecutor;
import depchain.config.NodeAddress;
import depchain.demo.MultiProcessConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.web3j.crypto.Credentials;

/** REPL for DepCoin / IST against running members (see README). */
public final class InteractiveCli {

    private static final int IST_DECIMALS = 2;
    private static final long GAS_PRICE = 1L;
    private static final long GAS_LIMIT_CONTRACT = 500_000L;
    private static final long GAS_LIMIT_NATIVE = 21_000L;

    /** Hardhat account 0 (matches genesis first account). */
    private static final String KEY_ACCT0 =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    /** Hardhat account 1 (matches genesis second account). */
    private static final String KEY_ACCT1 =
            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";

    private InteractiveCli() {}

    public static void main(String[] args) throws Exception {
        List<NodeAddress> targets = MultiProcessConfig.getClientTargets();
        int listenPort = MultiProcessConfig.CLIENT_LISTEN + 17;
        if (args.length >= 2 && "port".equalsIgnoreCase(args[0])) {
            listenPort = Integer.parseInt(args[1]);
        }
        InetSocketAddress bind = new InetSocketAddress("127.0.0.1", listenPort);
        System.out.println("Waiting 4s for replicas...");
        Thread.sleep(4000);

        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        String deployer = genesis.getAccounts().get(0).getAddress();
        String istContract = TransactionExecutor.deriveContractAddress(deployer, 0L);

        ConcurrentHashMap<String, AtomicLong> nonce = new ConcurrentHashMap<>();
        for (Genesis.GenesisAccount a : genesis.getAccounts()) {
            nonce.put(canonicalAddr(a.getAddress()), new AtomicLong(a.getNonce()));
        }

        Credentials[] creds = {
            Credentials.create(KEY_ACCT0),
            Credentials.create(KEY_ACCT1)
        };
        int accountIndex = 0;
        DepChainClient client =
                openClient(targets, listenPort, bind, creds[accountIndex]);

        System.out.println("DepChain interactive client (UDP " + bind + " → " + targets + ").");
        System.out.println("IST contract: " + istContract);
        System.out.println(
                "Commands: help | use 0|1 | balance dep <addr> | balance ist <addr> | native <to> <whole> |"
                    + " transfer <to> <whole.ist> | approve <spender> <whole.ist> | transferFrom <from> <to> <whole.ist> | quit");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        while (true) {
            System.out.print("depchain> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tok = line.split("\\s+");
            String cmd = tok[0].toLowerCase(Locale.ROOT);
            try {
                if ("quit".equals(cmd) || "exit".equals(cmd)) {
                    break;
                }
                if ("help".equals(cmd) || "?".equals(cmd)) {
                    printHelp();
                } else if ("use".equals(cmd) && tok.length >= 2) {
                    int idx = Integer.parseInt(tok[1]);
                    if (idx < 0 || idx > 1) {
                        System.out.println("use 0 or 1 only (genesis demo accounts).");
                    } else {
                        accountIndex = idx;
                        client.close();
                        client = openClient(targets, listenPort, bind, creds[accountIndex]);
                        System.out.println("Now signing as " + creds[accountIndex].getAddress());
                    }
                } else if ("balance".equals(cmd) && tok.length >= 3 && "dep".equalsIgnoreCase(tok[1])) {
                    long b = client.queryDepCoinBalance(canonicalAddr(tok[2]));
                    System.out.println("DepCoin balance: " + b);
                } else if ("balance".equals(cmd) && tok.length >= 3 && "ist".equalsIgnoreCase(tok[1])) {
                    BigInteger b =
                            client.queryIstBalanceOf(istContract, canonicalAddr(tok[2]));
                    System.out.println("IST smallest units: " + b + " (" + formatIstHuman(b) + " IST)");
                } else if ("native".equals(cmd) && tok.length >= 3) {
                    String from = canonicalAddr(Objects.requireNonNull(creds[accountIndex].getAddress()));
                    String to = canonicalAddr(tok[1]);
                    long whole = Long.parseLong(tok[2]);
                    long n = nonce.computeIfAbsent(from, k -> new AtomicLong(0L)).getAndIncrement();
                    Transaction tx =
                            new Transaction(
                                    from, to, n, whole, GAS_PRICE, GAS_LIMIT_NATIVE, null);
                    int idx = client.appendTransaction(tx);
                    System.out.println("append native tx -> chain index " + idx);
                } else if ("transfer".equals(cmd) && tok.length >= 3) {
                    String from = canonicalAddr(Objects.requireNonNull(creds[accountIndex].getAddress()));
                    String to = canonicalAddr(tok[1]);
                    BigInteger amount = parseIstHumanToUnits(tok[2]);
                    long n = nonce.computeIfAbsent(from, k -> new AtomicLong(0L)).getAndIncrement();
                    byte[] data = IstCoinCalldata.transfer(to, amount);
                    Transaction tx =
                            new Transaction(
                                    from, istContract, n, 0, GAS_PRICE, GAS_LIMIT_CONTRACT, data);
                    int idx = client.appendTransaction(tx);
                    System.out.println("IST transfer -> index " + idx);
                } else if ("approve".equals(cmd) && tok.length >= 3) {
                    String from = canonicalAddr(Objects.requireNonNull(creds[accountIndex].getAddress()));
                    String spender = canonicalAddr(tok[1]);
                    BigInteger amount = parseIstHumanToUnits(tok[2]);
                    long n = nonce.computeIfAbsent(from, k -> new AtomicLong(0L)).getAndIncrement();
                    byte[] data = IstCoinCalldata.approve(spender, amount);
                    Transaction tx =
                            new Transaction(
                                    from, istContract, n, 0, GAS_PRICE, GAS_LIMIT_CONTRACT, data);
                    int idx = client.appendTransaction(tx);
                    System.out.println("IST approve -> index " + idx);
                } else if ("transferfrom".equals(cmd) && tok.length >= 4) {
                    String spender = canonicalAddr(Objects.requireNonNull(creds[accountIndex].getAddress()));
                    String holder = canonicalAddr(tok[1]);
                    String to = canonicalAddr(tok[2]);
                    BigInteger amount = parseIstHumanToUnits(tok[3]);
                    long n = nonce.computeIfAbsent(spender, k -> new AtomicLong(0L)).getAndIncrement();
                    byte[] data = IstCoinCalldata.transferFrom(holder, to, amount);
                    Transaction tx =
                            new Transaction(
                                    spender,
                                    istContract,
                                    n,
                                    0,
                                    GAS_PRICE,
                                    GAS_LIMIT_CONTRACT,
                                    data);
                    int idx = client.appendTransaction(tx);
                    System.out.println("IST transferFrom (msg.sender must be approved spender) -> index " + idx);
                } else {
                    System.out.println("Unknown command; type help.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        client.close();
    }

    private static DepChainClient openClient(
            List<NodeAddress> targets, int listenPort, InetSocketAddress bind, Credentials cred) {
        return new DepChainClient(targets, listenPort, bind, null, cred, 20000L, 5);
    }

    /**
     * Lowercase 0x-prefixed hex so genesis JSON, EIP-55 from web3j, and user input share one map key.
     */
    private static String canonicalAddr(String a) {
        if (a == null || a.isBlank()) {
            return "";
        }
        String hex = org.web3j.utils.Numeric.cleanHexPrefix(a.trim());
        return "0x" + hex.toLowerCase(Locale.ROOT);
    }

    private static BigInteger parseIstHumanToUnits(String human) {
        String s = human.trim();
        int dot = s.indexOf('.');
        BigInteger whole;
        BigInteger frac;
        if (dot < 0) {
            whole = new BigInteger(s);
            frac = BigInteger.ZERO;
        } else {
            whole = new BigInteger(s.substring(0, dot).isEmpty() ? "0" : s.substring(0, dot));
            String f = s.substring(dot + 1);
            if (f.length() > IST_DECIMALS) {
                f = f.substring(0, IST_DECIMALS);
            }
            while (f.length() < IST_DECIMALS) {
                f = f + "0";
            }
            frac = f.isEmpty() ? BigInteger.ZERO : new BigInteger(f);
        }
        BigInteger factor = BigInteger.TEN.pow(IST_DECIMALS);
        return whole.multiply(factor).add(frac);
    }

    private static String formatIstHuman(BigInteger units) {
        if (units == null) {
            return "?";
        }
        BigInteger factor = BigInteger.TEN.pow(IST_DECIMALS);
        BigInteger[] dr = units.divideAndRemainder(factor);
        return dr[0].toString() + "." + String.format("%0" + IST_DECIMALS + "d", dr[1]);
    }

    private static void printHelp() {
        System.out.println(
                "use 0|1     — sign as genesis account 0 or 1 (run approve as #0, transferFrom as #1)\n"
                    + "balance dep — native DepCoin (Wei-style integer balance in genesis units)\n"
                    + "balance ist — ERC-20 balance in smallest units (2 decimals)\n"
                    + "native      — simple transfer of whole DepCoin units\n"
                    + "transfer    — IST transfer(to, amount) with decimal amount e.g. 12.5\n"
                    + "approve     — IST approve(spender, amount); then use 1 + transferFrom owner spender ...");
        System.out.println(
                "Typical: use 0; balance ist 0xf39F…92266 (deployer); transfer 0x7099…79C8 10.0;"
                    + " use 1; balance ist 0x7099…79C8. Approve path: use 0; approve <spender> 5.0; use 1;"
                    + " transferFrom <owner> <to> 2.0");
    }
}
