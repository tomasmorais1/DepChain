package depchain.links;

import depchain.config.Membership;
import depchain.config.NodeAddress;
import depchain.transport.FairLossLink;
import depchain.transport.UdpTransport;

import java.io.IOException;
import java.security.*;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated Perfect Links: (1) verify signature with sender's public key
 * first;
 * (2) then deduplicate by (senderId, messageId). Never deduplicate before
 * authenticating.
 * (3) Receiver sends ACK for each delivered message; sender retransmits until
 * ACK is received
 * (stops retransmissions to avoid infinite retries).
 */
public class AuthenticatedPerfectLink implements AutoCloseable {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final long RETRY_INTERVAL_MS = 200;
    private static final int MAX_SEND_ATTEMPTS = 60;

    private final int selfId;
    private final Membership membership;
    private final FairLossLink fairLoss;
    private final PrivateKey privateKey;
    private final Map<Integer, NodeAddress> idToAddress;
    private final Map<Long, Boolean> seenMessageIds = new ConcurrentHashMap<>();
    private static final int SEEN_MAX = 100_000;
    private final AtomicLong messageIdGenerator = new AtomicLong(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "apl-retry");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<Long, PendingSend> pendingSends = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AuthenticatedPerfectLink(int selfId, Membership membership, FairLossLink fairLoss, PrivateKey privateKey) {
        this.selfId = selfId;
        this.membership = Objects.requireNonNull(membership);
        this.fairLoss = Objects.requireNonNull(fairLoss);
        this.privateKey = Objects.requireNonNull(privateKey);
        this.idToAddress = new ConcurrentHashMap<>();
        for (int id : membership.getMemberIds()) {
            idToAddress.put(id, membership.getAddress(id));
        }
    }

    /** Send payload to a specific member. Assigns a new messageId. */
    public void send(byte[] payload, int destId) throws IOException {
        long messageId = messageIdGenerator.incrementAndGet();
        sendWithId(payload, destId, messageId);
    }

    /**
     * Send with explicit messageId. Retransmits until ACK received or max attempts.
     */
    public void sendWithId(byte[] payload, int destId, long messageId) throws IOException {
        APLMessage msg = new APLMessage(selfId, messageId, payload);
        byte[] signedContent = msg.getSignedContent();
        byte[] signature = sign(signedContent);
        byte[] wire = APLMessage.encode(signedContent, signature);
        NodeAddress dest = membership.getAddress(destId);
        if (dest == null)
            throw new IllegalArgumentException("unknown dest: " + destId);
        fairLoss.send(wire, dest);
        long key = pendingKey(messageId, destId);
        PendingSend pending = new PendingSend(wire, dest, 0);
        if (!closed.get()) {
            try {
                ScheduledFuture<?> future = scheduler.schedule(() -> retrySend(key, pending), RETRY_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
                pending.setFuture(future);
            } catch (RejectedExecutionException e) {
                // link closing; no retries
            }
        }
        pendingSends.put(key, pending);
    }

    private long pendingKey(long messageId, int destId) {
        return (messageId << 32) | (destId & 0xFFFFFFFFL);
    }

    private void retrySend(long key, PendingSend pending) {
        if (closed.get())
            return;
        PendingSend current = pendingSends.get(key);
        if (current != pending)
            return; // already acked and removed or replaced
        try {
            fairLoss.send(pending.wire, pending.dest);
        } catch (IOException ignored) {
        }
        int nextAttempt = pending.attempt + 1;
        if (nextAttempt >= MAX_SEND_ATTEMPTS) {
            pendingSends.remove(key);
            return;
        }
        PendingSend next = new PendingSend(pending.wire, pending.dest, nextAttempt);
        if (!closed.get()) {
            try {
                ScheduledFuture<?> future = scheduler.schedule(() -> retrySend(key, next), RETRY_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
                next.setFuture(future);
            } catch (RejectedExecutionException e) {
                // link closing
            }
        }
        pendingSends.put(key, next);
    }

    private void sendAck(int destId, long messageId) {
        try {
            APLMessage ack = APLMessage.createAck(selfId, messageId);
            byte[] signedContent = ack.getSignedContent();
            byte[] signature = sign(signedContent);
            byte[] wire = APLMessage.encode(signedContent, signature);
            NodeAddress dest = membership.getAddress(destId);
            if (dest != null)
                fairLoss.send(wire, dest);
        } catch (IOException ignored) {
        }
    }

    private static final class PendingSend {
        final byte[] wire;
        final NodeAddress dest;
        final int attempt;
        ScheduledFuture<?> future;

        PendingSend(byte[] wire, NodeAddress dest, int attempt) {
            this.wire = wire;
            this.dest = dest;
            this.attempt = attempt;
        }

        void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }
    }

    /**
     * Poll for a delivered message. Returns null if none available.
     * Process: read raw -> parse -> verify signature -> if ACK cancel
     * retransmissions;
     * if DATA deduplicate -> send ACK -> return.
     */
    public DeliveredMessage poll() {
        UdpTransport.RawMessage raw = fairLoss.poll();
        if (raw == null)
            return null;
        APLMessage.Parsed parsed = APLMessage.parse(raw.getPayload());
        if (parsed == null)
            return null;
        int senderId = parsed.getSenderId();
        if (!membership.isMember(senderId))
            return null;
        PublicKey pubKey = membership.getPublicKey(senderId);
        if (pubKey == null)
            return null;
        if (!verify(parsed.getSignedContent(), parsed.getSignature(), pubKey))
            return null;

        if (parsed.getType() == APLMessage.TYPE_ACK) {
            long key = pendingKey(parsed.getMessageId(), senderId);
            PendingSend removed = pendingSends.remove(key);
            if (removed != null && removed.future != null)
                removed.future.cancel(false);
            return null;
        }

        // DATA: deduplicate after authentication
        long dedupKey = dedupKey(senderId, parsed.getMessageId());
        if (seenMessageIds.putIfAbsent(dedupKey, Boolean.TRUE) != null)
            return null;
        pruneSeenIfNeeded();
        sendAck(senderId, parsed.getMessageId());
        return new DeliveredMessage(senderId, parsed.getPayload());
    }

    private long dedupKey(int senderId, long messageId) {
        return ((long) senderId << 32) | (messageId & 0xFFFFFFFFL);
    }

    private void pruneSeenIfNeeded() {
        if (seenMessageIds.size() > SEEN_MAX) {
            seenMessageIds.keySet().stream().limit(SEEN_MAX / 2).forEach(seenMessageIds::remove);
        }
    }

    private byte[] sign(byte[] data) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("sign failed", e);
        }
    }

    private boolean verify(byte[] data, byte[] signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        fairLoss.close();
    }

    public static final class DeliveredMessage {
        private final int senderId;
        private final byte[] payload;

        DeliveredMessage(int senderId, byte[] payload) {
            this.senderId = senderId;
            this.payload = payload;
        }

        public int getSenderId() {
            return senderId;
        }

        public byte[] getPayload() {
            return payload;
        }
    }
}
