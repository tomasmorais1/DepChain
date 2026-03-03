package depchain.links;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Wire format for Authenticated Perfect Links: senderId, messageId, payload.
 * Signature is computed over (senderId, messageId, payload) and sent separately.
 */
public final class APLMessage {
    private final int senderId;
    private final long messageId;
    private final byte[] payload;

    public APLMessage(int senderId, long messageId, byte[] payload) {
        this.senderId = senderId;
        this.messageId = messageId;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    /** Bytes to be signed (senderId + messageId + payload). */
    public byte[] getSignedContent() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(senderId);
        d.writeLong(messageId);
        d.writeInt(payload.length);
        d.write(payload);
        d.flush();
        return out.toByteArray();
    }

    public int getSenderId() { return senderId; }
    public long getMessageId() { return messageId; }
    public byte[] getPayload() { return payload; }

    /** Full serialization: signedContentLength (4), signedContent, sigLength (2), signature. */
    public static byte[] encode(byte[] signedContent, byte[] signature) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(signedContent.length);
        d.write(signedContent);
        d.writeShort(signature.length);
        d.write(signature);
        d.flush();
        return out.toByteArray();
    }

    /** Parse and verify: returns null if invalid or malformed. */
    public static Parsed parse(byte[] wire) {
        if (wire == null || wire.length < 4 + 8 + 4 + 2) return null;
        ByteBuffer b = ByteBuffer.wrap(wire);
        int contentLen = b.getInt();
        if (contentLen < 0 || b.remaining() < contentLen + 2) return null;
        byte[] signedContent = new byte[contentLen];
        b.get(signedContent);
        int sigLen = b.getShort() & 0xFFFF;
        if (b.remaining() < sigLen) return null;
        byte[] signature = new byte[sigLen];
        b.get(signature);
        // Parse signed content: senderId, messageId, payloadLen, payload
        if (signedContent.length < 4 + 8 + 4) return null;
        ByteBuffer c = ByteBuffer.wrap(signedContent);
        int senderId = c.getInt();
        long messageId = c.getLong();
        int payloadLen = c.getInt();
        if (payloadLen < 0 || c.remaining() < payloadLen) return null;
        byte[] payload = new byte[payloadLen];
        c.get(payload);
        return new Parsed(senderId, messageId, payload, signedContent, signature);
    }

    public static final class Parsed {
        private final int senderId;
        private final long messageId;
        private final byte[] payload;
        private final byte[] signedContent;
        private final byte[] signature;

        Parsed(int senderId, long messageId, byte[] payload, byte[] signedContent, byte[] signature) {
            this.senderId = senderId;
            this.messageId = messageId;
            this.payload = payload;
            this.signedContent = signedContent;
            this.signature = signature;
        }

        public int getSenderId() { return senderId; }
        public long getMessageId() { return messageId; }
        public byte[] getPayload() { return payload; }
        public byte[] getSignedContent() { return signedContent; }
        public byte[] getSignature() { return signature; }
    }
}
