package depchain.consensus;

import depchain.config.Membership;
import threshsig.GroupKey;
import threshsig.KeyShare;
import threshsig.SigShare;
import threshsig.ThreshSigWire;
import threshsig.ThresholdSigException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HotStuff replica using threshold signatures (Shoup's scheme).
 * Votes are signature shares; QC is verified with SigShare.verify.
 */
public class HotStuffReplica implements AutoCloseable {
    private static final String VOTE_HASH_ALGORITHM = "SHA-256";

    private final int selfId;
    private final Membership membership;
    private final ConsensusNetwork network;
    private final KeyShare keyShare;
    private final GroupKey groupKey;
    private final DecideCallback decideCallback;

    private final long viewTimeoutMs;
    private final AtomicLong view = new AtomicLong(0);
    private volatile Phase phase = Phase.PREPARE;
    private volatile Block currentBlock;
    private volatile byte[] lockedBlockHash;
    private volatile QuorumCertificate highQC;
    private final Map<Integer, SigShare> votes = new HashMap<>();
    /** Per-view set of replica ids that sent NEW_VIEW (leader only sends PREPARE after n-f). */
    private final Map<Long, Set<Integer>> newViewSendersByView = new HashMap<>();
    /** Last view for which we sent NEW_VIEW (replicas send NEW_VIEW once per view to leader). */
    private long lastViewForWhichWeSentNewView = -1;
    private long lastNewViewWireSendTimeMs = 0L;
    /** Resend NEW_VIEW while in the same view (UDP can drop; leader needs n-f distinct senders). */
    private static final long NEW_VIEW_RESEND_MS = 250L;
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
            KeyShare keyShare, GroupKey groupKey, DecideCallback decideCallback) {
        this(selfId, membership, network, keyShare, groupKey, decideCallback, null, 2000L);
    }

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
            KeyShare keyShare, GroupKey groupKey, DecideCallback decideCallback, long viewTimeoutMs) {
        this(selfId, membership, network, keyShare, groupKey, decideCallback, null, viewTimeoutMs);
    }

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
            KeyShare keyShare, GroupKey groupKey, DecideCallback decideCallback, BlockValidator blockValidator) {
        this(selfId, membership, network, keyShare, groupKey, decideCallback, blockValidator, 2000L);
    }

    public HotStuffReplica(int selfId, Membership membership, ConsensusNetwork network,
            KeyShare keyShare, GroupKey groupKey, DecideCallback decideCallback,
            BlockValidator blockValidator, long viewTimeoutMs) {
        this.selfId = selfId;
        this.membership = membership;
        this.network = network;
        this.keyShare = keyShare;
        this.groupKey = groupKey;
        this.decideCallback = decideCallback;
        this.blockValidator = blockValidator;
        this.viewTimeoutMs = viewTimeoutMs > 0 ? viewTimeoutMs : 2000L;
        this.processThread = new Thread(this::processLoop, "hotstuff-" + selfId);
        this.processThread.setDaemon(true);
        this.processThread.start();
    }

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
                if (isLeader()) tryProposePending();
                sendNewViewIfNeeded();
                if (System.currentTimeMillis() - lastProgressTimeMs > viewTimeoutMs) {
                    onViewTimeout();
                }
                if (msg == null) Thread.sleep(5);
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

    /** Non-leaders send NEW_VIEW to the leader so the leader can wait for n-f before PREPARE. */
    private void sendNewViewIfNeeded() {
        if (isLeader()) return;
        long v = view.get();
        long now = System.currentTimeMillis();
        boolean firstInView = v > lastViewForWhichWeSentNewView;
        boolean resendSameView =
            v == lastViewForWhichWeSentNewView && now - lastNewViewWireSendTimeMs >= NEW_VIEW_RESEND_MS;
        if (!firstInView && !resendSameView) return;
        try {
            byte[] wire = ConsensusMessage.encodeNewView(v);
            network.sendTo(membership.getLeaderId(v), wire);
            lastViewForWhichWeSentNewView = v;
            lastNewViewWireSendTimeMs = now;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void touchProgress() { lastProgressTimeMs = System.currentTimeMillis(); }

    private void tryProposePending() {
        Block stale = pendingProposals.peek();
        if (stale == null) return;
        long v = view.get();
        if (phase != Phase.PREPARE) return;
        int requiredNewViews = membership.getN() - membership.getF();
        Set<Integer> newViewSenders = newViewSendersByView.get(v);
        if (newViewSenders == null || newViewSenders.size() < requiredNewViews)
            return;
        pendingProposals.poll();
        // Block must use the current view (propose() may have been queued under an older view).
        Block block = new Block(v, stale.getPayload());
        currentBlock = block;
        try {
            byte[] wire = ConsensusMessage.encodePrepare(v, block, highQC);
            network.sendToAll(wire);
            System.err.println("[member " + selfId + "] leader sent PREPARE view " + v);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private boolean isLeader() { return membership.getLeaderId(view.get()) == selfId; }

    private static String typeName(byte type) {
        switch (type) {
            case ConsensusMessage.TYPE_PREPARE: return "PREPARE";
            case ConsensusMessage.TYPE_PREPARE_VOTE: return "PREPARE_VOTE";
            case ConsensusMessage.TYPE_PRE_COMMIT: return "PRE_COMMIT";
            case ConsensusMessage.TYPE_PRE_COMMIT_VOTE: return "PRE_COMMIT_VOTE";
            case ConsensusMessage.TYPE_COMMIT: return "COMMIT";
            case ConsensusMessage.TYPE_COMMIT_VOTE: return "COMMIT_VOTE";
            case ConsensusMessage.TYPE_DECIDE: return "DECIDE";
            case ConsensusMessage.TYPE_NEW_VIEW: return "NEW_VIEW";
            default: return "?";
        }
    }

    private void logConsensusRecv(int senderId, byte[] payload) {
        ConsensusMessage.Message m = ConsensusMessage.parse(payload);
        if (m != null) System.err.println("[member " + selfId + "] consensus recv " + typeName(m.getType()) + " from " + senderId);
    }

    private void handleMessage(int senderId, byte[] payload) {
        ConsensusMessage.Message msg = ConsensusMessage.parse(payload);
        if (msg == null) return;
        if (msg.getView() < view.get()) return;
        switch (msg.getType()) {
            case ConsensusMessage.TYPE_PREPARE: handlePrepare(senderId, msg); break;
            case ConsensusMessage.TYPE_PREPARE_VOTE: handleVote(senderId, msg, Phase.PREPARE, this::encodePreCommit); break;
            case ConsensusMessage.TYPE_PRE_COMMIT: handlePreCommit(senderId, msg); break;
            case ConsensusMessage.TYPE_PRE_COMMIT_VOTE: handleVote(senderId, msg, Phase.PRE_COMMIT, this::encodeCommit); break;
            case ConsensusMessage.TYPE_COMMIT: handleCommit(senderId, msg); break;
            case ConsensusMessage.TYPE_COMMIT_VOTE: handleVote(senderId, msg, Phase.COMMIT, this::encodeDecide); break;
            case ConsensusMessage.TYPE_DECIDE: handleDecide(msg); break;
            case ConsensusMessage.TYPE_NEW_VIEW: handleNewView(senderId, msg); break;
            default: break;
        }
    }

    /**
     * Followers send NEW_VIEW to leader(v). If replicas' local views drift (independent timeouts),
     * the leader may be behind: accept v &gt; view and sync before counting senders.
     */
    private void handleNewView(int senderId, ConsensusMessage.Message msg) {
        long v = msg.getView();
        if (v < view.get()) return;
        if (v > view.get()) {
            view.set(v);
            phase = Phase.PREPARE;
            currentBlock = null;
            votes.clear();
            lockedBlockHash = null;
            lastProgressTimeMs = System.currentTimeMillis();
        }
        if (membership.getLeaderId(v) != selfId) return;
        newViewSendersByView.computeIfAbsent(v, k -> new HashSet<>()).add(senderId);
    }

    private void handlePrepare(int senderId, ConsensusMessage.Message msg) {
        long v = msg.getView();
        if (v > view.get()) {
            view.set(v);
            phase = Phase.PREPARE;
            currentBlock = null;
            votes.clear();
            lockedBlockHash = null;
        } else if (v < view.get()) view.set(v);
        if (membership.getLeaderId(v) != senderId) return;
        Block block = msg.getBlock();
        if (block == null) return;
        if (blockValidator != null && !blockValidator.validate(block)) return;
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
        if (v > view.get()) { view.set(v); phase = Phase.PREPARE; currentBlock = null; votes.clear(); }
        else if (v < view.get()) view.set(v);
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
        if (v > view.get()) { view.set(v); phase = Phase.PREPARE; currentBlock = null; votes.clear(); }
        else if (v < view.get()) view.set(v);
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
        if (v > view.get()) { view.set(v); phase = Phase.PREPARE; currentBlock = null; votes.clear(); }
        else if (v < view.get()) view.set(v);
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
        sendNewViewToLeaderForCurrentView();
    }

    /** Send NEW_VIEW to the leader for the current view (used after DECIDE so leader receives n-f before next PREPARE). */
    private void sendNewViewToLeaderForCurrentView() {
        long v = view.get();
        if (membership.getLeaderId(v) == selfId) return;
        try {
            byte[] wire = ConsensusMessage.encodeNewView(v);
            network.sendTo(membership.getLeaderId(v), wire);
            lastViewForWhichWeSentNewView = v;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleVote(int senderId, ConsensusMessage.Message msg, Phase expectedPhase,
            EncodePhaseMessage encoder) {
        long v = msg.getView();
        if (v != view.get() || !isLeader() || phase != expectedPhase || currentBlock == null) return;
        if (!java.util.Arrays.equals(msg.getVoteBlockHash(), currentBlock.getBlockHash())) return;
        SigShare sigShare;
        try {
            sigShare = ThreshSigWire.decodeSigShare(msg.getVoteSignature());
        } catch (IOException e) {
            return;
        }
        if (sigShare == null) return;
        if (votes.containsKey(senderId)) return;
        votes.put(senderId, sigShare);
        touchProgress();

        if (votes.size() >= membership.getQuorumSize()) {
            List<SigShare> allShares = new ArrayList<>(votes.values());
            byte[] content = voteContent(v, expectedPhase, currentBlock.getBlockHash());
            byte[] hash = hashVoteContent(content);
            SigShare[] validSubset = findVerifyingSubset(allShares, membership.getQuorumSize(), hash);
            if (validSubset == null) return;
            votes.clear();
            QuorumCertificate fullQC = new QuorumCertificate(v, expectedPhase, currentBlock.getBlockHash(), groupKey, validSubset);
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
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    /** Find a subset of size quorum that passes SigShare.verify (tolerates Byzantine/corrupt shares). */
    private SigShare[] findVerifyingSubset(List<SigShare> shares, int quorum, byte[] voteHash) {
        if (shares.size() < quorum) return null;
        int k = groupKey.getK();
        int l = groupKey.getL();
        return combine(shares, 0, quorum, new SigShare[quorum], 0, voteHash, k, l);
    }

    private SigShare[] combine(List<SigShare> source, int srcIdx, int need, SigShare[] chosen, int chosenIdx,
            byte[] voteHash, int k, int l) {
        if (need == 0) {
            try {
                if (SigShare.verify(voteHash, chosen, k, l, groupKey.getModulus(), groupKey.getExponent()))
                    return chosen.clone();
            } catch (ThresholdSigException e) {
                // this combination failed (e.g. duplicate id or invalid sig); try next
            }
            return null;
        }
        for (int i = srcIdx; i <= source.size() - need; i++) {
            chosen[chosenIdx] = source.get(i);
            SigShare[] result = combine(source, i + 1, need - 1, chosen, chosenIdx + 1, voteHash, k, l);
            if (result != null) return result;
        }
        return null;
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
        byte[] content = voteContent(v, phaseForVoteType(voteType), blockHash);
        byte[] hash = hashVoteContent(content);
        SigShare sigShare = keyShare.sign(hash);
        try {
            byte[] sigWire = ThreshSigWire.encodeSigShare(sigShare);
            byte[] wire;
            if (voteType == ConsensusMessage.TYPE_PREPARE_VOTE)
                wire = ConsensusMessage.encodePrepareVote(v, blockHash, sigWire);
            else if (voteType == ConsensusMessage.TYPE_PRE_COMMIT_VOTE)
                wire = ConsensusMessage.encodePreCommitVote(v, blockHash, sigWire);
            else if (voteType == ConsensusMessage.TYPE_COMMIT_VOTE)
                wire = ConsensusMessage.encodeCommitVote(v, blockHash, sigWire);
            else return;
            network.sendTo(membership.getLeaderId(v), wire);
        } catch (IOException e) { e.printStackTrace(); }
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
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    private static byte[] hashVoteContent(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance(VOTE_HASH_ALGORITHM);
            md.update(content);
            return md.digest();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    private boolean verifyQC(QuorumCertificate qc) {
        if (qc.getVoteCount() < membership.getQuorumSize()) return false;
        byte[] content = voteContent(qc.getViewNumber(), qc.getPhase(), qc.getBlockHash());
        byte[] hash = hashVoteContent(content);
        try {
            return SigShare.verify(hash, qc.getSigShares(), groupKey.getK(), groupKey.getL(),
                    groupKey.getModulus(), groupKey.getExponent());
        } catch (ThresholdSigException e) {
            return false;
        }
    }

    @Override
    public void close() {
        running.set(false);
        processThread.interrupt();
    }

    public long getView() { return view.get(); }
}
