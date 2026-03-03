package depchain.config;

import java.security.PublicKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Static membership: ids, addresses, and public keys of all blockchain members.
 * Well-known to all participants before system start.
 */
public final class Membership {
    private final List<Integer> memberIds;
    private final Map<Integer, NodeAddress> addresses;
    private final Map<Integer, PublicKey> publicKeys;
    private final int n;
    private final int f;

    public Membership(List<Integer> memberIds,
                      Map<Integer, NodeAddress> addresses,
                      Map<Integer, PublicKey> publicKeys) {
        this.memberIds = List.copyOf(Objects.requireNonNull(memberIds));
        this.addresses = Map.copyOf(Objects.requireNonNull(addresses));
        this.publicKeys = Map.copyOf(Objects.requireNonNull(publicKeys));
        this.n = memberIds.size();
        if (n < 1) throw new IllegalArgumentException("empty membership");
        this.f = (n - 1) / 3;
        for (int id : memberIds) {
            if (!addresses.containsKey(id) || !publicKeys.containsKey(id))
                throw new IllegalArgumentException("missing address or key for member " + id);
        }
    }

    public List<Integer> getMemberIds() { return Collections.unmodifiableList(memberIds); }
    public NodeAddress getAddress(int memberId) { return addresses.get(memberId); }
    public PublicKey getPublicKey(int memberId) { return publicKeys.get(memberId); }
    public int getN() { return n; }
    public int getF() { return f; }
    public int getQuorumSize() { return 2 * f + 1; }

    /** Leader for a given view (viewNumber mod n). */
    public int getLeaderId(long viewNumber) {
        int index = (int) (Math.floorMod(viewNumber, n));
        return memberIds.get(index);
    }

    public boolean isMember(int id) { return addresses.containsKey(id); }
}
