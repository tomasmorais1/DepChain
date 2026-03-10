package depchain.consensus;

import depchain.config.Membership;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Basic HotStuff replica: 4 phases (PREPARE, PRE_COMMIT, COMMIT, DECIDE).
 * On receiving DECIDE message: execute upcall then advance view.
 */
public class HotStuffReplica implements AutoCloseable {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final int selfId;
    private final Membership membership;
    private final ConsensusNetwork network;
    private final PrivateKey privateKey;
    private final DecideCallback decideCallback;

    private final long viewTimeoutMs;
    private final AtomicLong view = new AtomicLong(0);
    private volatile Phase phase = Phase.PREPARE;
    private volatile Block currentBlock;
    private volatile byte[] lockedBlockHash; // null or hash we pre-committed on
    private volatile QuorumCertificate highQC; // for next PREPARE
    private final Map<Integer, VoteInfo> votes = new HashMap<>(); // replicaId -> (blockHash, signature)
    private final BlockingQueue<Block> pendingProposals = new LinkedBlockingQueue<>();
    private final Thread processThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile long lastProgressTimeMs = System.currentTimeMillis();

    public interface DecideCallback {
        void onDecide(Block block);
    }

    
    public interface BlockValidator {
        boolean validate(Block block);
    }

    private final BlockValidator blockValidator;

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
                           PrivateKey privateKey, DecideCallback decideCallback) {
        this(selfId, membership, network, privateKey, decideCallback, null, 2000L);
    }

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
                           PrivateKey privateKey, DecideCallback decideCallback, long viewTimeoutMs) {
        this(selfId, membership, network, privateKey, decideCallback, null, viewTimeoutMs);
    }

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
                           PrivateKey privateKey, DecideCallback decideCallback, BlockValidator blockValidator) {
        this(selfId, membership, network, privateKey, decideCallback, blockValidator, 2000L);
    }

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
                           PrivateKey privateKey, DecideCallback decideCallback, BlockValidator blockValidator, long viewTimeoutMs) {
        this.selfId = selfId;
        this.membership = membership;
        this.network = network;
        this.privateKey = privateKey;
        this.decideCallback = decideCallback;
        this.blockValidator = blockValidator;
        this.viewTimeoutMs = viewTimeoutMs > 0 ? viewTimeoutMs : 2000L;
        this.processThread = new Thread(this::processLoop, "hotstuff-" + selfId);
        this.processThread.setDaemon(true);
        this.processThread.start();
    }

    /** Leader only: propose a block for the current view. */
    public void propose(Block block) {
        if (block == null) return;
        pendingProposals.offer(block);
    }

    private void processLoop() {
        while (running.get()) {
            try {
                ConsensusNetwork.ReceivedMessage msg = network.poll();
                if (msg != null) {
                    logConsensusRecv(msg.getSenderId(), msg.getPayload());
                    handleMessage(msg.getSenderId(), msg.getPayload());
                }
                if (isLeader()) {
                    tryProposePending();
                }
                if (System.currentTimeMillis() - lastProgressTimeMs > viewTimeoutMs) {
                    onViewTimeout();
                }
                if (msg == null) {
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void onViewTimeout() {
        view.incrementAndGet();
        phase = Phase.PREPARE;
        currentBlock = null;
        votes.clear();
        lastProgressTimeMs = System.currentTimeMillis();
    }

    private void touchProgress() {
        lastProgressTimeMs = System.currentTimeMillis();
    }

    private void tryProposePending() {
        Block block = pendingProposals.poll();
        if (block == null) return;
        long v = view.get();
        if (phase != Phase.PREPARE) return; // already in progress
        currentBlock = block;
        try {
            byte[] wire = ConsensusMessage.encodePrepare(v, block, highQC);
            network.sendToAll(wire);
            System.err.println("[member " + selfId + "] leader sent PREPARE view " + v);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isLeader() {
        return membership.getLeaderId(view.get()) == selfId;
    }

    private static String typeName(byte type) {
        switch (type) {
            case ConsensusMessage.TYPE_PREPARE: return "PREPARE";
            case ConsensusMessage.TYPE_PREPARE_VOTE: return "PREPARE_VOTE";
            case ConsensusMessage.TYPE_PRE_COMMIT: return "PRE_COMMIT";
            case ConsensusMessage.TYPE_PRE_COMMIT_VOTE: return "PRE_COMMIT_VOTE";
            case ConsensusMessage.TYPE_COMMIT: return "COMMIT";
            case ConsensusMessage.TYPE_COMMIT_VOTE: return "COMMIT_VOTE";
            case ConsensusMessage.TYPE_DECIDE: return "DECIDE";
            default: return "?";
        }
    }

    private void logConsensusRecv(int senderId, byte[] payload) {
        ConsensusMessage.Message m = ConsensusMessage.parse(payload);
        if (m != null)
            System.err.println("[member " + selfId + "] consensus recv " + typeName(m.getType()) + " from " + senderId);
    }

    private void handleMessage(int senderId, byte[] payload) {
        ConsensusMessage.Message msg = ConsensusMessage.parse(payload);
        if (msg == null) return;
        long v = msg.getView();
        if (v < view.get()) return;

        switch (msg.getType()) {
            case ConsensusMessage.TYPE_PREPARE:
                handlePrepare(senderId, msg);
                break;
            case ConsensusMessage.TYPE_PREPARE_VOTE:
                handleVote(senderId, msg, Phase.PREPARE, ConsensusMessage.TYPE_PRE_COMMIT, this::encodePreCommit);
                break;
            case ConsensusMessage.TYPE_PRE_COMMIT:
                handlePreCommit(senderId, msg);
                break;
            case ConsensusMessage.TYPE_PRE_COMMIT_VOTE:
                handleVote(senderId, msg, Phase.PRE_COMMIT, ConsensusMessage.TYPE_COMMIT, this::encodeCommit);
                break;
            case ConsensusMessage.TYPE_COMMIT:
                handleCommit(senderId, msg);
                break;
            case ConsensusMessage.TYPE_COMMIT_VOTE:
                handleVote(senderId, msg, Phase.COMMIT, ConsensusMessage.TYPE_DECIDE, this::encodeDecide);
                break;
            case ConsensusMessage.TYPE_DECIDE:
                handleDecide(msg);
                break;
            default:
                break;
        }
    }

    private void handlePrepare(int senderId, ConsensusMessage.Message msg) {
        long v = msg.getView();
        if (v > view.get()) {
            // catch up to leader's view so we can vote (replicas behind were ignoring PREPARE and never sent votes)
            view.set(v);
            phase = Phase.PREPARE;
            currentBlock = null;
            votes.clear();
            lockedBlockHash = null;
        } else if (v < view.get()) {
            view.set(v);
        }
        if (membership.getLeaderId(v) != senderId) return;
        Block block = msg.getBlock();
        if (block == null) return;
        if (blockValidator != null && !blockValidator.validate(block)) return; // reject forged commands from byzantine leader
        QuorumCertificate qc = msg.getQC();
        if (lockedBlockHash != null && !java.util.Arrays.equals(block.getBlockHash(), lockedBlockHash)) {
            if (qc == null || qc.getViewNumber() >= v) return;
        }
        touchProgress();
        currentBlock = block;
        phase = Phase.PREPARE;
        sendVote(ConsensusMessage.TYPE_PREPARE_VOTE, v, block.getBlockHash());
    }

    private void handlePreCommit(int senderId, ConsensusMessage.Message msg) {
        long v = msg.getView();
        if (v > view.get()) {
            view.set(v);
            phase = Phase.PREPARE;
            currentBlock = null;
            votes.clear();
        } else if (v < view.get()) {
            view.set(v);
        }
        if (membership.getLeaderId(v) != senderId) return;
        Block block = msg.getBlock();
        QuorumCertificate prepareQC = msg.getQC();
        if (block == null || prepareQC == null) return;
        if (prepareQC.getPhase() != Phase.PREPARE) return;
        if (!java.util.Arrays.equals(prepareQC.getBlockHash(), block.getBlockHash())) return;
        if (!verifyQC(prepareQC)) return;
        touchProgress();
        currentBlock = block;
        lockedBlockHash = block.getBlockHash();
        phase = Phase.PRE_COMMIT;
        sendVote(ConsensusMessage.TYPE_PRE_COMMIT_VOTE, v, block.getBlockHash());
    }

    private void handleCommit(int senderId, ConsensusMessage.Message msg) {
        long v = msg.getView();
        if (v > view.get()) {
            view.set(v);
            phase = Phase.PREPARE;
            currentBlock = null;
            votes.clear();
        } else if (v < view.get()) {
            view.set(v);
        }
        if (membership.getLeaderId(v) != senderId) return;
        Block block = msg.getBlock();
        QuorumCertificate preCommitQC = msg.getQC();
        if (block == null || preCommitQC == null) return;
        if (preCommitQC.getPhase() != Phase.PRE_COMMIT) return;
        if (!java.util.Arrays.equals(preCommitQC.getBlockHash(), block.getBlockHash())) return;
        if (!verifyQC(preCommitQC)) return;
        touchProgress();
        currentBlock = block;
        phase = Phase.COMMIT;
        sendVote(ConsensusMessage.TYPE_COMMIT_VOTE, v, block.getBlockHash());
    }

    private void handleDecide(ConsensusMessage.Message msg) {
        long v = msg.getView();
        if (v > view.get()) {
            view.set(v);
            phase = Phase.PREPARE;
            currentBlock = null;
            votes.clear();
        } else if (v < view.get()) {
            view.set(v);
        }
        QuorumCertificate commitQC = msg.getQC();
        if (commitQC == null || commitQC.getPhase() != Phase.COMMIT) return;
        if (!verifyQC(commitQC)) return;
        Block block = currentBlock;
        if (block == null || !java.util.Arrays.equals(block.getBlockHash(), commitQC.getBlockHash())) return;
        touchProgress();
        highQC = commitQC;
        decideCallback.onDecide(block);
        view.incrementAndGet();
        phase = Phase.PREPARE;
        currentBlock = null;
    }

    private void handleVote(int senderId, ConsensusMessage.Message msg, Phase expectedPhase, byte nextType,
                            EncodePhaseMessage encoder) {
        long v = msg.getView();
        if (v != view.get()) return; // leader only counts votes for its current view
        if (!isLeader()) return;
        if (phase != expectedPhase) return;
        if (currentBlock == null) return;
        if (!java.util.Arrays.equals(msg.getVoteBlockHash(), currentBlock.getBlockHash())) return;
        if (!verifyVoteSignature(msg.getView(), expectedPhase, msg.getVoteBlockHash(), msg.getVoteSignature(), senderId)) return;
        if (votes.containsKey(senderId)) return;
        votes.put(senderId, new VoteInfo(msg.getVoteBlockHash(), msg.getVoteSignature()));
        touchProgress();

        if (votes.size() >= membership.getQuorumSize()) {
            QuorumCertificate qc = new QuorumCertificate(v, expectedPhase, currentBlock.getBlockHash(), new HashMap<>());
            Map<Integer, byte[]> sigs = new HashMap<>();
            for (Map.Entry<Integer, VoteInfo> e : votes.entrySet()) {
                sigs.put(e.getKey(), e.getValue().signature);
            }
            QuorumCertificate fullQC = new QuorumCertificate(v, expectedPhase, currentBlock.getBlockHash(), sigs);
            votes.clear();
            phase = nextPhase(expectedPhase);
            try {
                byte[] wire = encoder.encode(v, currentBlock, fullQC);
                network.sendToAll(wire);
                if (expectedPhase == Phase.COMMIT) {
                    highQC = fullQC;
                    decideCallback.onDecide(currentBlock);
                    view.incrementAndGet();
                    phase = Phase.PREPARE;
                    currentBlock = null;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Phase nextPhase(Phase p) {
        switch (p) {
            case PREPARE: return Phase.PRE_COMMIT;
            case PRE_COMMIT: return Phase.COMMIT;
            case COMMIT: return Phase.DECIDE;
            default: return Phase.PREPARE;
        }
    }

    private interface EncodePhaseMessage {
        byte[] encode(long view, Block block, QuorumCertificate qc) throws IOException;
    }

    private byte[] encodePreCommit(long view, Block block, QuorumCertificate qc) throws IOException {
        return ConsensusMessage.encodePreCommit(view, block, qc);
    }
    private byte[] encodeCommit(long view, Block block, QuorumCertificate qc) throws IOException {
        return ConsensusMessage.encodeCommit(view, block, qc);
    }
    private byte[] encodeDecide(long view, Block block, QuorumCertificate qc) throws IOException {
        return ConsensusMessage.encodeDecide(view, qc);
    }

    private void sendVote(byte voteType, long v, byte[] blockHash) {
        byte[] toSign = voteContent(v, phaseForVoteType(voteType), blockHash);
        byte[] sig = sign(toSign);
        try {
            byte[] wire;
            if (voteType == ConsensusMessage.TYPE_PREPARE_VOTE) wire = ConsensusMessage.encodePrepareVote(v, blockHash, sig);
            else if (voteType == ConsensusMessage.TYPE_PRE_COMMIT_VOTE) wire = ConsensusMessage.encodePreCommitVote(v, blockHash, sig);
            else if (voteType == ConsensusMessage.TYPE_COMMIT_VOTE) wire = ConsensusMessage.encodeCommitVote(v, blockHash, sig);
            else return;
            int leaderId = membership.getLeaderId(v);
            network.sendTo(leaderId, wire);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Phase phaseForVoteType(byte type) {
        if (type == ConsensusMessage.TYPE_PREPARE_VOTE) return Phase.PREPARE;
        if (type == ConsensusMessage.TYPE_PRE_COMMIT_VOTE) return Phase.PRE_COMMIT;
        if (type == ConsensusMessage.TYPE_COMMIT_VOTE) return Phase.COMMIT;
        return Phase.PREPARE;
    }

    private byte[] voteContent(long view, Phase phase, byte[] blockHash) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeLong(view);
            d.writeByte(phase.ordinal());
            d.writeInt(blockHash.length);
            d.write(blockHash);
            d.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] sign(byte[] data) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean verifyVoteSignature(long view, Phase phase, byte[] blockHash, byte[] signature, int replicaId) {
        PublicKey pub = membership.getPublicKey(replicaId);
        if (pub == null) return false;
        byte[] content = voteContent(view, phase, blockHash);
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(pub);
            sig.update(content);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private boolean verifyQC(QuorumCertificate qc) {
        if (qc.getVoteCount() < membership.getQuorumSize()) return false;
        for (Map.Entry<Integer, byte[]> e : qc.getSignatures().entrySet()) {
            int id = e.getKey();
            byte[] sig = e.getValue();
            if (!verifyVoteSignature(qc.getViewNumber(), qc.getPhase(), qc.getBlockHash(), sig, id)) return false;
        }
        return true;
    }

    private static final class VoteInfo {
        final byte[] blockHash;
        final byte[] signature;
        VoteInfo(byte[] blockHash, byte[] signature) {
            this.blockHash = blockHash;
            this.signature = signature;
        }
    }

    @Override
    public void close() {
        running.set(false);
        processThread.interrupt();
    }

    public long getView() { return view.get(); }
}
