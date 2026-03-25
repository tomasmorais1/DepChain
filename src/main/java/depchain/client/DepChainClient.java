package depchain.client;

import depchain.blockchain.Transaction;
import depchain.blockchain.TransactionCommandCodec;
import depchain.client.eth.TransactionEthHasher;
import depchain.config.NodeAddress;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client library: append(string) broadcasts signed request to all members,
 * waits for f+1 identical responses before accepting (cannot trust first responder).
 * Clients sign requests with their private key so replicas can reject commands
 * forged by a byzantine leader. {@link #append(String)} uses RSA (legacy/Demo);
 * {@link #appendTransaction(Transaction)} uses secp256k1 + Keccak-256 (Ethereum-style).
 */
public final class DepChainClient implements AutoCloseable {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final List<NodeAddress> memberAddresses;
    private final int f;
    private final int requiredResponses;
    private final int clientPort;
    private final long timeoutMs;
    private final int maxRetries;
    private final KeyPair keyPair;
    /** Required for {@link #appendTransaction(Transaction)}; optional for RSA-only {@link #append(String)}. */
    private final Credentials ethCredentials;
    private final AtomicLong requestIdGen = new AtomicLong(0);
    private volatile DatagramSocket socket;
    private final Map<Long, ResponseAccumulator> pending = new ConcurrentHashMap<>();
    private final Thread receiverThread;
    private volatile boolean closed;

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort) {
        this(memberAddresses, clientPort, null, 5000L, 3);
    }

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, null, timeoutMs, maxRetries);
    }

    /**
     * Create client; if bindAddress is non-null, bind the receive socket to that
     * address (e.g. 127.0.0.1 for multi-JVM so responses reach us).
     * If keyPair is null, a new key pair is generated for this client.
     */
    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, InetSocketAddress bindAddress,
            long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, bindAddress, null, timeoutMs, maxRetries);
    }

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, InetSocketAddress bindAddress,
            KeyPair keyPair, long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, bindAddress, keyPair, null, timeoutMs, maxRetries);
    }

    /**
     * @param ethCredentials used to sign Stage-2 transactions ({@link #appendTransaction}); may be null for RSA-only clients.
     */
    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, InetSocketAddress bindAddress,
            KeyPair keyPair, Credentials ethCredentials, long timeoutMs, int maxRetries) {
        this.memberAddresses = List.copyOf(memberAddresses);
        int n = this.memberAddresses.size();
        this.f = (n - 1) / 3;
        this.requiredResponses = f + 1;
        this.clientPort = clientPort;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.keyPair = keyPair != null ? keyPair : generateKeyPair();
        this.ethCredentials = ethCredentials;
        try {
            this.socket = bindAddress != null
                    ? new DatagramSocket(bindAddress)
                    : new DatagramSocket(clientPort);
        } catch (Exception e) {
            throw new RuntimeException("bind client port " + clientPort, e);
        }
        this.receiverThread = new Thread(this::receiveLoop, "client-receiver");
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Append string to the blockchain. Blocks until a member confirms or timeout.
     * 
     * @return the index at which the string was appended, or -1 on failure
     */
    public int append(String string) throws InterruptedException {
        if (string == null)
            return -1;
        long requestId = requestIdGen.incrementAndGet();
        byte[] content = ClientProtocol.signedContent(requestId, string);
        byte[] signature = sign(content);
        byte[] publicKeyEncoded = keyPair.getPublic().getEncoded();
        byte[] payload = ClientProtocol.encodeRequest(requestId, string, signature, publicKeyEncoded);
        ResponseAccumulator acc = new ResponseAccumulator(requiredResponses);
        pending.put(requestId, acc);
        try {
            for (int r = 0; r < maxRetries; r++) {
                try {
                    System.err.println("[client] send requestId=" + requestId + " to " + memberAddresses);
                    for (NodeAddress addr : memberAddresses) {
                        DatagramPacket p = new DatagramPacket(payload, payload.length, addr.toInetSocketAddress());
                        socket.send(p);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    ClientProtocol.Response resp = acc.future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    System.err.println("[client] received f+1 identical responses requestId=" + requestId
                            + " index=" + resp.getIndex() + " success=" + resp.isSuccess());
                    return resp.isSuccess() ? resp.getIndex() : -1;
                } catch (TimeoutException e) {
                    System.err.println(
                            "[client] timeout requestId=" + requestId + " retry " + (r + 1) + "/" + maxRetries);
                    continue;
                } catch (ExecutionException e) {
                    return -1;
                }
            }
            System.err.println("[client] all retries failed for requestId=" + requestId);
            return -1;
        } finally {
            pending.remove(requestId);
        }
    }

    /** Submit a full Stage 2 transaction: Keccak-256 + ECDSA; replicas recover {@code from}. */
    public int appendTransaction(Transaction tx) throws InterruptedException {
        if (tx == null) {
            return -1;
        }
        if (ethCredentials == null) {
            throw new IllegalStateException("ETH Credentials required for appendTransaction (use DepChainClient constructor with Credentials)");
        }
        String fromNorm = Numeric.cleanHexPrefix(TransactionEthHasher.normalizeAddr(tx.getFrom()));
        String credNorm = Numeric.cleanHexPrefix(ethCredentials.getAddress());
        if (!fromNorm.equalsIgnoreCase(credNorm)) {
            throw new IllegalArgumentException("tx.from must match client ETH address: tx=" + tx.getFrom() + " cred=" + ethCredentials.getAddress());
        }
        String encoded = TransactionCommandCodec.encode(tx);
        long requestId = requestIdGen.incrementAndGet();
        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        Sign.SignatureData sig = Sign.signMessage(hash, ethCredentials.getEcKeyPair(), false);
        byte[] payload = ClientProtocol.encodeEthRequest(requestId, encoded, sig);
        ResponseAccumulator acc = new ResponseAccumulator(requiredResponses);
        pending.put(requestId, acc);
        try {
            for (int r = 0; r < maxRetries; r++) {
                try {
                    System.err.println("[client] send ETH requestId=" + requestId + " to " + memberAddresses);
                    for (NodeAddress addr : memberAddresses) {
                        DatagramPacket p = new DatagramPacket(payload, payload.length, addr.toInetSocketAddress());
                        socket.send(p);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    ClientProtocol.Response resp = acc.future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    System.err.println("[client] received f+1 identical responses requestId=" + requestId
                            + " index=" + resp.getIndex() + " success=" + resp.isSuccess());
                    return resp.isSuccess() ? resp.getIndex() : -1;
                } catch (TimeoutException e) {
                    System.err.println(
                            "[client] timeout requestId=" + requestId + " retry " + (r + 1) + "/" + maxRetries);
                    continue;
                } catch (ExecutionException e) {
                    return -1;
                }
            }
            System.err.println("[client] all retries failed for requestId=" + requestId);
            return -1;
        } finally {
            pending.remove(requestId);
        }
    }

    /** Accumulates responses until requiredResponses (f+1) identical (same success, index) are received. */
    private static final class ResponseAccumulator {
        final CompletableFuture<ClientProtocol.Response> future = new CompletableFuture<>();
        final int required;
        final List<ClientProtocol.Response> responses = new ArrayList<>();

        ResponseAccumulator(int required) {
            this.required = required;
        }

        synchronized void add(ClientProtocol.Response r) {
            if (future.isDone()) return;
            responses.add(r);
            for (ClientProtocol.Response x : responses) {
                long count = responses.stream()
                        .filter(y -> y.getIndex() == x.getIndex() && y.isSuccess() == x.isSuccess())
                        .count();
                if (count >= required) {
                    future.complete(x);
                    return;
                }
            }
        }
    }

    private byte[] sign(byte[] data) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(keyPair.getPrivate());
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("client sign failed", e);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[1024];
        while (!closed && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                byte[] copy = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                ClientProtocol.Response resp = ClientProtocol.parseResponse(copy);
                if (resp != null) {
                    System.err.println("[client] recv response requestId=" + resp.getRequestId() + " from "
                            + p.getSocketAddress());
                    ResponseAccumulator acc = pending.get(resp.getRequestId());
                    if (acc != null)
                        acc.add(resp);
                }
            } catch (IOException e) {
                if (!closed)
                    e.printStackTrace();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (socket != null)
            socket.close();
        receiverThread.interrupt();
    }
}
