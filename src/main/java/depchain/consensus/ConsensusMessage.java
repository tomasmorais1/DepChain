package depchain.consensus;

import threshsig.GroupKey;
import threshsig.SigShare;
import threshsig.ThreshSigWire;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format for HotStuff consensus messages.
 * Type 1=PREPARE, 2=PREPARE_VOTE, 3=PRE_COMMIT, 4=PRE_COMMIT_VOTE, 5=COMMIT, 6=COMMIT_VOTE, 7=DECIDE, 8=NEW_VIEW.
 */
public final class ConsensusMessage {

    public static final byte TYPE_PREPARE = 1;
    public static final byte TYPE_PREPARE_VOTE = 2;
    public static final byte TYPE_PRE_COMMIT = 3;
    public static final byte TYPE_PRE_COMMIT_VOTE = 4;
    public static final byte TYPE_COMMIT = 5;
    public static final byte TYPE_COMMIT_VOTE = 6;
    public static final byte TYPE_DECIDE = 7;
    public static final byte TYPE_NEW_VIEW = 8;

    public static byte[] encodePrepare(long view, Block block, QuorumCertificate highQC) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_PREPARE);
        d.writeLong(view);
        writeBlock(d, block);
        writeQC(d, highQC);
        d.flush();
        return out.toByteArray();
    }

    /** Encode vote with threshold signature share (sigShareWire = ThreshSigWire.encodeSigShare(sigShare)). */
    public static byte[] encodePrepareVote(long view, byte[] blockHash, byte[] sigShareWire) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_PREPARE_VOTE);
        d.writeLong(view);
        d.writeInt(blockHash.length);
        d.write(blockHash);
        d.writeInt(sigShareWire.length);
        d.write(sigShareWire);
        d.flush();
        return out.toByteArray();
    }

    public static byte[] encodePreCommit(long view, Block block, QuorumCertificate prepareQC) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_PRE_COMMIT);
        d.writeLong(view);
        writeBlock(d, block);
        writeQC(d, prepareQC);
        d.flush();
        return out.toByteArray();
    }

    public static byte[] encodePreCommitVote(long view, byte[] blockHash, byte[] sigShareWire) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_PRE_COMMIT_VOTE);
        d.writeLong(view);
        d.writeInt(blockHash.length);
        d.write(blockHash);
        d.writeInt(sigShareWire.length);
        d.write(sigShareWire);
        d.flush();
        return out.toByteArray();
    }

    public static byte[] encodeCommit(long view, Block block, QuorumCertificate preCommitQC) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_COMMIT);
        d.writeLong(view);
        writeBlock(d, block);
        writeQC(d, preCommitQC);
        d.flush();
        return out.toByteArray();
    }

    public static byte[] encodeCommitVote(long view, byte[] blockHash, byte[] sigShareWire) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_COMMIT_VOTE);
        d.writeLong(view);
        d.writeInt(blockHash.length);
        d.write(blockHash);
        d.writeInt(sigShareWire.length);
        d.write(sigShareWire);
        d.flush();
        return out.toByteArray();
    }

    public static byte[] encodeDecide(long view, QuorumCertificate commitQC) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_DECIDE);
        d.writeLong(view);
        writeQC(d, commitQC);
        d.flush();
        return out.toByteArray();
    }

    /** New view: replica declares it is in the given view (leader waits for n-f before sending PREPARE). */
    public static byte[] encodeNewView(long view) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(TYPE_NEW_VIEW);
        d.writeLong(view);
        d.flush();
        return out.toByteArray();
    }

    private static void writeBlock(DataOutputStream d, Block block) throws IOException {
        if (block == null) {
            d.writeBoolean(false);
            return;
        }
        d.writeBoolean(true);
        d.writeLong(block.getViewNumber());
        d.writeInt(block.getPayload().length);
        d.write(block.getPayload());
    }

    private static Block readBlock(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        long view = in.readLong();
        int len = in.readInt();
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Block(view, payload);
    }

    private static void writeQC(DataOutputStream d, QuorumCertificate qc) throws IOException {
        if (qc == null) {
            d.writeBoolean(false);
            return;
        }
        d.writeBoolean(true);
        d.writeLong(qc.getViewNumber());
        d.writeByte(qc.getPhase().ordinal());
        d.writeInt(qc.getBlockHash().length);
        d.write(qc.getBlockHash());
        byte[] gkBytes = qc.getGroupKey().toBytes();
        d.writeInt(gkBytes.length);
        d.write(gkBytes);
        d.writeInt(qc.getSigShares().length);
        for (SigShare s : qc.getSigShares()) {
            byte[] b = ThreshSigWire.encodeSigShare(s);
            d.writeInt(b.length);
            d.write(b);
        }
    }

    private static QuorumCertificate readQC(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        long view = in.readLong();
        Phase phase = Phase.values()[in.readByte()];
        int hashLen = in.readInt();
        byte[] blockHash = new byte[hashLen];
        in.readFully(blockHash);
        int gkLen = in.readInt();
        byte[] gkBytes = new byte[gkLen];
        in.readFully(gkBytes);
        GroupKey groupKey = GroupKey.fromBytes(gkBytes);
        int n = in.readInt();
        List<SigShare> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            byte[] sb = new byte[len];
            in.readFully(sb);
            list.add(ThreshSigWire.decodeSigShare(sb));
        }
        return new QuorumCertificate(view, phase, blockHash, groupKey, list.toArray(new SigShare[0]));
    }

    public static Message parse(byte[] wire) {
        if (wire == null || wire.length < 1) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire));
            byte type = in.readByte();
            long view = in.readLong();
            switch (type) {
                case TYPE_PREPARE:
                    return new Message(type, view, readBlock(in), readQC(in), null, null);
                case TYPE_PREPARE_VOTE:
                    return readVote(in, type, view);
                case TYPE_PRE_COMMIT:
                    return new Message(type, view, readBlock(in), readQC(in), null, null);
                case TYPE_PRE_COMMIT_VOTE:
                    return readVote(in, type, view);
                case TYPE_COMMIT:
                    return new Message(type, view, readBlock(in), readQC(in), null, null);
                case TYPE_COMMIT_VOTE:
                    return readVote(in, type, view);
                case TYPE_DECIDE:
                    return new Message(type, view, null, readQC(in), null, null);
                case TYPE_NEW_VIEW:
                    return new Message(type, view, null, null, null, null);
                default:
                    return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static Message readVote(DataInputStream in, byte type, long view) throws IOException {
        int hashLen = in.readInt();
        byte[] blockHash = new byte[hashLen];
        in.readFully(blockHash);
        int sigLen = in.readInt();
        byte[] signature = new byte[sigLen];
        in.readFully(signature);
        return new Message(type, view, null, null, blockHash, signature);
    }

    public static final class Message {
        private final byte type;
        private final long view;
        private final Block block;
        private final QuorumCertificate qc;
        private final byte[] voteBlockHash;
        private final byte[] voteSignature;

        Message(byte type, long view, Block block, QuorumCertificate qc, byte[] voteBlockHash, byte[] voteSignature) {
            this.type = type;
            this.view = view;
            this.block = block;
            this.qc = qc;
            this.voteBlockHash = voteBlockHash;
            this.voteSignature = voteSignature;
        }

        public byte getType() { return type; }
        public long getView() { return view; }
        public Block getBlock() { return block; }
        public QuorumCertificate getQC() { return qc; }
        public byte[] getVoteBlockHash() { return voteBlockHash; }
        public byte[] getVoteSignature() { return voteSignature; }
    }
}
