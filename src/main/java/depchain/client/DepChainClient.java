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
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP client: broadcast to all members; accept after {@code f+1} matching replies.
 * {@link #append(String)} RSA (legacy); {@link #appendTransaction(Transaction)} ETH signing.
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
    private final Credentials ethCredentials;
    private final AtomicLong requestIdGen = new AtomicLong(0);
    private volatile DatagramSocket socket;
    private final Map<Long, ResponseAccumulator> pending = new ConcurrentHashMap<>();
    private final Map<Long, QueryAccumulator> pendingQueries = new ConcurrentHashMap<>();
    private final Thread receiverThread;
    private volatile boolean closed;

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort) {
        this(memberAddresses, clientPort, null, 5000L, 3);
    }

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, null, timeoutMs, maxRetries);
    }

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, InetSocketAddress bindAddress,
            long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, bindAddress, null, timeoutMs, maxRetries);
    }

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, InetSocketAddress bindAddress,
            KeyPair keyPair, long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, bindAddress, keyPair, null, timeoutMs, maxRetries);
    }

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

    /** @return decided index, or -1 */
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

    public long queryDepCoinBalance(String address) throws InterruptedException {
        return queryDepCoinBalance(address, 0);
    }

    public long queryDepCoinBalance(String address, int minHeadHeight) throws InterruptedException {
        long requestId = requestIdGen.incrementAndGet();
        byte[] payload = ClientProtocol.encodeQueryRequest(requestId, ClientProtocol.QUERY_DEP_BALANCE, address);
        QueryAccumulator acc = new QueryAccumulator(requiredResponses, minHeadHeight);
        pendingQueries.put(requestId, acc);
        try {
            for (int r = 0; r < maxRetries; r++) {
                try {
                    for (NodeAddress addr : memberAddresses) {
                        DatagramPacket p = new DatagramPacket(payload, payload.length, addr.toInetSocketAddress());
                        socket.send(p);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    ClientProtocol.QueryResponse resp = acc.future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    if (!resp.isSuccess() || resp.getReturnData().length != 8) return -1L;
                    return java.nio.ByteBuffer.wrap(resp.getReturnData()).getLong();
                } catch (TimeoutException e) {
                    continue;
                } catch (ExecutionException e) {
                    return -1L;
                }
            }
            return -1L;
        } finally {
            pendingQueries.remove(requestId);
        }
    }

    public java.math.BigInteger queryIstBalanceOf(String contractAddress, String address) throws InterruptedException {
        return queryIstBalanceOf(contractAddress, address, 0);
    }

    public java.math.BigInteger queryIstBalanceOf(String contractAddress, String address, int minHeadHeight)
            throws InterruptedException {
        String payloadStr = contractAddress + "|" + address;
        long requestId = requestIdGen.incrementAndGet();
        byte[] payload = ClientProtocol.encodeQueryRequest(requestId, ClientProtocol.QUERY_IST_BALANCE_OF, payloadStr);
        QueryAccumulator acc = new QueryAccumulator(requiredResponses, minHeadHeight);
        pendingQueries.put(requestId, acc);
        try {
            for (int r = 0; r < maxRetries; r++) {
                try {
                    for (NodeAddress addr : memberAddresses) {
                        DatagramPacket p = new DatagramPacket(payload, payload.length, addr.toInetSocketAddress());
                        socket.send(p);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    ClientProtocol.QueryResponse resp = acc.future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    if (!resp.isSuccess()) return java.math.BigInteger.valueOf(-1);
                    byte[] rd = resp.getReturnData();
                    if (rd.length == 0) return java.math.BigInteger.ZERO;
                    return new java.math.BigInteger(1, rd);
                } catch (TimeoutException e) {
                    continue;
                } catch (ExecutionException e) {
                    return java.math.BigInteger.valueOf(-1);
                }
            }
            return java.math.BigInteger.valueOf(-1);
        } finally {
            pendingQueries.remove(requestId);
        }
    }

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

    private static final class QueryAccumulator {
        final CompletableFuture<ClientProtocol.QueryResponse> future = new CompletableFuture<>();
        final int required;
        final int minHeadHeight;
        final List<ClientProtocol.QueryResponse> responses = new ArrayList<>();

        QueryAccumulator(int required, int minHeadHeight) {
            this.required = required;
            this.minHeadHeight = Math.max(0, minHeadHeight);
        }

        synchronized void add(ClientProtocol.QueryResponse r) {
            if (future.isDone()) return;
            if (r.getHeadHeight() < minHeadHeight) return;
            responses.add(r);
            for (ClientProtocol.QueryResponse x : responses) {
                long count = responses.stream()
                    .filter(y ->
                        y.isSuccess() == x.isSuccess()
                            && y.getHeadHeight() == x.getHeadHeight()
                            && Objects.equals(y.getHeadHash(), x.getHeadHash())
                            && java.util.Arrays.equals(y.getReturnData(), x.getReturnData())
                    )
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
                ClientProtocol.QueryResponse q = ClientProtocol.parseQueryResponse(copy);
                if (q != null) {
                    QueryAccumulator acc = pendingQueries.get(q.getRequestId());
                    if (acc != null) acc.add(q);
                    continue;
                }
                ClientProtocol.Response resp = ClientProtocol.parseResponse(copy);
                if (resp != null) {
                    ResponseAccumulator acc = pending.get(resp.getRequestId());
                    if (acc != null) {
                        System.err.println("[client] recv response requestId=" + resp.getRequestId() + " from "
                                + p.getSocketAddress());
                        acc.add(resp);
                    }
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
