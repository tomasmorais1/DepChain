package depchain.demo;

import depchain.config.Membership;
import depchain.config.NodeAddress;

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
 * Shared config for multi-JVM demo. Same ports as Demo.
 * Use genconfig once to write keys to a file; all member processes load from that file so keys match.
 */
public final class MultiProcessConfig {
    public static final int N = 4;
    public static final int BASE_CONSENSUS = 30000;
    public static final int BASE_CLIENT = 30100;
    public static final int CLIENT_LISTEN = 30200;
    public static final long VIEW_TIMEOUT_MS = 2000L;

    /** Default key file in current directory (run from project root). */
    public static final String DEFAULT_KEY_FILE = "depchain-multijvm.keys";

    /**
     * Generate 4 key pairs and write to file. Run once before starting any member.
     */
    public static void writeKeysToFile(Path path) throws IOException, GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(N);
            for (int i = 0; i < N; i++) {
                KeyPair kp = gen.generateKeyPair();
                byte[] pub = kp.getPublic().getEncoded();
                byte[] priv = kp.getPrivate().getEncoded();
                out.writeInt(pub.length);
                out.write(pub);
                out.writeInt(priv.length);
                out.write(priv);
            }
        }
    }

    /**
     * Load membership and private key for the given member from a key file.
     */
    public static class MemberConfig {
        public final Membership membership;
        public final PrivateKey privateKey;

        public MemberConfig(Membership membership, PrivateKey privateKey) {
            this.membership = membership;
            this.privateKey = privateKey;
        }
    }

    public static MemberConfig loadMemberFromFile(Path path, int memberId) throws IOException, GeneralSecurityException {
        return loadMemberFromFile(path, memberId, BASE_CONSENSUS, BASE_CLIENT);
    }

    /** Load with custom port base (e.g. for tests to avoid port conflict). */
    public static MemberConfig loadMemberFromFile(Path path, int memberId, int consensusPortBase, int clientPortBase) throws IOException, GeneralSecurityException {
        if (memberId < 0 || memberId >= N) throw new IllegalArgumentException("member id " + memberId);
        List<Integer> ids = new ArrayList<>();
        Map<Integer, NodeAddress> addrs = new ConcurrentHashMap<>();
        Map<Integer, PublicKey> pubs = new ConcurrentHashMap<>();
        PrivateKey myPrivate = null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            int n = in.readInt();
            if (n != N) throw new IOException("Key file has N=" + n + ", expected " + N);
            KeyFactory kf = KeyFactory.getInstance("RSA");
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
            }
        }
        if (myPrivate == null) throw new IllegalStateException("member id " + memberId);
        return new MemberConfig(new Membership(ids, addrs, pubs), myPrivate);
    }

    /**
     * Client addresses (member client ports) for DepChainClient.
     */
    public static List<NodeAddress> getClientTargets() {
        return getClientTargets(BASE_CLIENT);
    }

    public static List<NodeAddress> getClientTargets(int clientPortBase) {
        List<NodeAddress> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            out.add(new NodeAddress("127.0.0.1", clientPortBase + i));
        }
        return out;
    }
}
