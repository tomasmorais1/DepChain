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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated Perfect Links: (1) verify signature with sender's public key first;
 * (2) then deduplicate by (senderId, messageId). Never deduplicate before authenticating.
 */
public class AuthenticatedPerfectLink implements AutoCloseable {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final int selfId;
    private final Membership membership;
    private final FairLossLink fairLoss;
    private final PrivateKey privateKey;
    private final Map<Integer, NodeAddress> idToAddress;
    private final Map<Long, Boolean> seenMessageIds = new ConcurrentHashMap<>();
    private static final int SEEN_MAX = 100_000;
    private final AtomicLong messageIdGenerator = new AtomicLong(0);

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

    /** Send with explicit messageId (e.g. for retries, same id). */
    public void sendWithId(byte[] payload, int destId, long messageId) throws IOException {
        APLMessage msg = new APLMessage(selfId, messageId, payload);
        byte[] signedContent = msg.getSignedContent();
        byte[] signature = sign(signedContent);
        byte[] wire = APLMessage.encode(signedContent, signature);
        NodeAddress dest = membership.getAddress(destId);
        if (dest == null) throw new IllegalArgumentException("unknown dest: " + destId);
        fairLoss.send(wire, dest);
    }

    /**
     * Poll for a delivered message. Returns null if none available.
     * Process: read raw -> parse -> verify signature -> deduplicate -> return.
     */
    public DeliveredMessage poll() {
        UdpTransport.RawMessage raw = fairLoss.poll();
        if (raw == null) return null;
        APLMessage.Parsed parsed = APLMessage.parse(raw.getPayload());
        if (parsed == null) return null;
        int senderId = parsed.getSenderId();
        if (!membership.isMember(senderId)) return null;
        PublicKey pubKey = membership.getPublicKey(senderId);
        if (pubKey == null) return null;
        if (!verify(parsed.getSignedContent(), parsed.getSignature(), pubKey)) return null;
        // Deduplicate after authentication
        long dedupKey = dedupKey(senderId, parsed.getMessageId());
        if (seenMessageIds.putIfAbsent(dedupKey, Boolean.TRUE) != null) return null;
        pruneSeenIfNeeded();
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
        fairLoss.close();
    }

    public static final class DeliveredMessage {
        private final int senderId;
        private final byte[] payload;

        DeliveredMessage(int senderId, byte[] payload) {
            this.senderId = senderId;
            this.payload = payload;
        }

        public int getSenderId() { return senderId; }
        public byte[] getPayload() { return payload; }
    }
}
