package depchain.consensus;

import depchain.config.Membership;
import depchain.links.AuthenticatedPerfectLink;

import java.io.IOException;

/** Consensus network over APL: broadcast to all members. */
public final class APLConsensusNetwork implements ConsensusNetwork {
    private final AuthenticatedPerfectLink apl;
    private final Membership membership;

    public APLConsensusNetwork(AuthenticatedPerfectLink apl, Membership membership) {
        this.apl = apl;
        this.membership = membership;
    }

    @Override
    public void sendToAll(byte[] payload) throws IOException {
        for (int id : membership.getMemberIds()) {
            apl.send(payload, id);
        }
    }

    @Override
    public void sendTo(int memberId, byte[] payload) throws IOException {
        apl.send(payload, memberId);
    }

    @Override
    public ReceivedMessage poll() {
        AuthenticatedPerfectLink.DeliveredMessage d = apl.poll();
        if (d == null) return null;
        return new ReceivedMessage() {
            @Override
            public int getSenderId() { return d.getSenderId(); }
            @Override
            public byte[] getPayload() { return d.getPayload(); }
        };
    }
}
