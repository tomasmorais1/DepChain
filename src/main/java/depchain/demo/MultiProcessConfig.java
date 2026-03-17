package depchain.demo;

import depchain.config.Membership;
import depchain.config.NodeAddress;
import threshsig.Dealer;
import threshsig.GroupKey;
import threshsig.KeyShare;
import threshsig.ThresholdSigException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared config for multi-JVM demo.
 * Generates APL keys (Ed25519) per member and one threshold key set (GroupKey + KeyShare per member).
 */
public final class MultiProcessConfig {
    public static final int N = 4;
    public static final int BASE_CONSENSUS = 30000;
    public static final int BASE_CLIENT = 30100;
    public static final int CLIENT_LISTEN = 30200;
    public static final long VIEW_TIMEOUT_MS = 2000L;
    private static final int THRESH_KEYSIZE = 512;

    public static final String DEFAULT_KEY_FILE = "depchain-multijvm.keys";

    /**
     * Generate APL keys (RSA, SHA256withRSA) and threshold keys (Dealer); write to file.
     */
    public static void writeKeysToFile(Path path) throws IOException, GeneralSecurityException {
        KeyPairGenerator aplGen = KeyPairGenerator.getInstance("RSA");
        aplGen.initialize(2048);
        Dealer dealer = new Dealer(THRESH_KEYSIZE);
        int quorum = 2 * (N - 1) / 3 + 1;
        dealer.generateKeys(quorum, N);
        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] shares = dealer.getShares();

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(N);
            byte[] gkBytes = groupKey.toBytes();
            out.writeInt(gkBytes.length);
            out.write(gkBytes);
            for (int i = 0; i < N; i++) {
                KeyPair kp = aplGen.generateKeyPair();
                byte[] pub = kp.getPublic().getEncoded();
                byte[] priv = kp.getPrivate().getEncoded();
                out.writeInt(pub.length);
                out.write(pub);
                out.writeInt(priv.length);
                out.write(priv);
                byte[] shareBytes = shares[i].encodeForFile();
                out.writeInt(shareBytes.length);
                out.write(shareBytes);
            }
        }
    }

    public static class MemberConfig {
        public final Membership membership;
        public final PrivateKey privateKey;
        public final KeyShare keyShare;
        public final GroupKey groupKey;

        public MemberConfig(Membership membership, PrivateKey privateKey, KeyShare keyShare, GroupKey groupKey) {
            this.membership = membership;
            this.privateKey = privateKey;
            this.keyShare = keyShare;
            this.groupKey = groupKey;
        }
    }

    public static MemberConfig loadMemberFromFile(Path path, int memberId)
            throws IOException, GeneralSecurityException {
        return loadMemberFromFile(path, memberId, BASE_CONSENSUS, BASE_CLIENT);
    }

    public static MemberConfig loadMemberFromFile(Path path, int memberId, int consensusPortBase, int clientPortBase)
            throws IOException, GeneralSecurityException {
        if (memberId < 0 || memberId >= N) throw new IllegalArgumentException("member id " + memberId);
        List<Integer> ids = new ArrayList<>();
        Map<Integer, NodeAddress> addrs = new ConcurrentHashMap<>();
        Map<Integer, PublicKey> pubs = new ConcurrentHashMap<>();
        PrivateKey myPrivate = null;
        KeyShare myKeyShare = null;
        GroupKey groupKey = null;
        KeyFactory kf = KeyFactory.getInstance("RSA");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            int n = in.readInt();
            if (n != N) throw new IOException("Key file has N=" + n + ", expected " + N);
            int gkLen = in.readInt();
            byte[] gkBytes = new byte[gkLen];
            in.readFully(gkBytes);
            groupKey = GroupKey.fromBytes(gkBytes);
            for (int i = 0; i < N; i++) {
                ids.add(i);
                addrs.put(i, new NodeAddress("127.0.0.1", consensusPortBase + i));
                int pubLen = in.readInt();
                byte[] pubBytes = new byte[pubLen];
                in.readFully(pubBytes);
                pubs.put(i, kf.generatePublic(new X509EncodedKeySpec(pubBytes)));
                int privLen = in.readInt();
                byte[] privBytes = new byte[privLen];
                in.readFully(privBytes);
                if (i == memberId) myPrivate = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                int shareLen = in.readInt();
                byte[] shareBytes = new byte[shareLen];
                in.readFully(shareBytes);
                if (i == memberId) myKeyShare = KeyShare.decodeFromFile(shareBytes);
            }
        }
        if (myPrivate == null || myKeyShare == null || groupKey == null)
            throw new IllegalStateException("member id " + memberId);
        return new MemberConfig(new Membership(ids, addrs, pubs), myPrivate, myKeyShare, groupKey);
    }

    public static List<NodeAddress> getClientTargets() { return getClientTargets(BASE_CLIENT); }

    public static List<NodeAddress> getClientTargets(int clientPortBase) {
        List<NodeAddress> out = new ArrayList<>();
        for (int i = 0; i < N; i++) out.add(new NodeAddress("127.0.0.1", clientPortBase + i));
        return out;
    }
}
