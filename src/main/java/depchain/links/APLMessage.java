package depchain.links;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Wire format for Authenticated Perfect Links: type (DATA/ACK), senderId, messageId, payload.
 * Signature is computed over (type, senderId, messageId, payload) and sent separately.
 * ACK messages allow the sender to stop retransmitting once the receiver has acknowledged.
 */
public final class APLMessage {
    public static final byte TYPE_DATA = 0;
    public static final byte TYPE_ACK = 1;

    private final byte type;
    private final int senderId;
    private final long messageId;
    private final byte[] payload;

    /** Data message (application payload). */
    public APLMessage(int senderId, long messageId, byte[] payload) {
        this.type = TYPE_DATA;
        this.senderId = senderId;
        this.messageId = messageId;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    /** ACK message: senderId = node that received the data, messageId = id of the message being ack'd. */
    public static APLMessage createAck(int senderId, long messageId) {
        APLMessage m = new APLMessage(TYPE_ACK, senderId, messageId, new byte[0]);
        return m;
    }

    private APLMessage(byte type, int senderId, long messageId, byte[] payload) {
        this.type = type;
        this.senderId = senderId;
        this.messageId = messageId;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    /** Bytes to be signed (type + senderId + messageId + for DATA: payloadLen + payload). */
    public byte[] getSignedContent() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(type);
        d.writeInt(senderId);
        d.writeLong(messageId);
        if (type == TYPE_DATA) {
            d.writeInt(payload.length);
            d.write(payload);
        }
        d.flush();
        return out.toByteArray();
    }

    public byte getType() { return type; }

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
        if (wire == null || wire.length < 4 + 1 + 4 + 8 + 2) return null;
        ByteBuffer b = ByteBuffer.wrap(wire);
        int contentLen = b.getInt();
        if (contentLen < 0 || b.remaining() < contentLen + 2) return null;
        byte[] signedContent = new byte[contentLen];
        b.get(signedContent);
        int sigLen = b.getShort() & 0xFFFF;
        if (b.remaining() < sigLen) return null;
        byte[] signature = new byte[sigLen];
        b.get(signature);
        ByteBuffer c = ByteBuffer.wrap(signedContent);
        if (c.remaining() < 1 + 4 + 8) return null;
        byte type = c.get();
        int senderId = c.getInt();
        long messageId = c.getLong();
        byte[] payload;
        if (type == TYPE_DATA) {
            if (c.remaining() < 4) return null;
            int payloadLen = c.getInt();
            if (payloadLen < 0 || c.remaining() < payloadLen) return null;
            payload = new byte[payloadLen];
            c.get(payload);
        } else if (type == TYPE_ACK) {
            payload = new byte[0];
        } else {
            return null;
        }
        return new Parsed(type, senderId, messageId, payload, signedContent, signature);
    }

    public static final class Parsed {
        private final byte type;
        private final int senderId;
        private final long messageId;
        private final byte[] payload;
        private final byte[] signedContent;
        private final byte[] signature;

        Parsed(byte type, int senderId, long messageId, byte[] payload, byte[] signedContent, byte[] signature) {
            this.type = type;
            this.senderId = senderId;
            this.messageId = messageId;
            this.payload = payload;
            this.signedContent = signedContent;
            this.signature = signature;
        }

        public byte getType() { return type; }
        public int getSenderId() { return senderId; }
        public long getMessageId() { return messageId; }
        public byte[] getPayload() { return payload; }
        public byte[] getSignedContent() { return signedContent; }
        public byte[] getSignature() { return signature; }
    }
}
