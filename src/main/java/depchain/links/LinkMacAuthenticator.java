package depchain.links;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairwise link authentication using ECDH-derived static keys and HMAC-SHA256, similar in spirit
 * to symmetric MAC schemes used in closed-membership BFT demos (e.g. pre-shared secrets per pair).
 * Each ordered pair {@code (i,j)} uses {@code HMAC-SHA256(K_ij, signedContent)} where
 * {@code K_ij = SHA-256(ECDH(priv_i, pub_j))} (P-256 / secp256r1).
 */
public final class LinkMacAuthenticator {

    private final int selfId;
    private final int n;
    /** Indexed by peer id; entry is null for self or missing peer. */
    private final byte[][] macKeys;

    private LinkMacAuthenticator(int selfId, int n, byte[][] macKeys) {
        this.selfId = selfId;
        this.n = n;
        this.macKeys = macKeys;
    }

    /**
     * Builds MAC keys from this node's EC private key and all members' EC public keys (same order as ids 0..n-1).
     */
    public static LinkMacAuthenticator fromEcMaterial(
        int selfId,
        int n,
        PrivateKey ecPrivateSelf,
        java.util.List<PublicKey> ecPublicOrderedById
    ) {
        if (ecPrivateSelf == null || ecPublicOrderedById == null || ecPublicOrderedById.size() != n) {
            throw new IllegalArgumentException("invalid EC material");
        }
        byte[][] keys = new byte[n][];
        for (int j = 0; j < n; j++) {
            if (j == selfId) {
                keys[j] = null;
                continue;
            }
            PublicKey peer = ecPublicOrderedById.get(j);
            if (peer == null) {
                throw new IllegalArgumentException("missing EC public for " + j);
            }
            keys[j] = deriveMacKey(ecPrivateSelf, peer);
        }
        return new LinkMacAuthenticator(selfId, n, keys);
    }

    private static byte[] deriveMacKey(PrivateKey priv, PublicKey pub) {
        try {
            javax.crypto.KeyAgreement ka = javax.crypto.KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(pub, true);
            byte[] secret = ka.generateSecret();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest(secret);
        } catch (Exception e) {
            throw new RuntimeException("ECDH derivation failed", e);
        }
    }

    public byte[] macForPeer(int peerId, byte[] signedContent) {
        if (peerId < 0 || peerId >= n || peerId == selfId) {
            throw new IllegalArgumentException("invalid peer " + peerId);
        }
        byte[] key = macKeys[peerId];
        if (key == null) {
            throw new IllegalStateException("no MAC key for peer " + peerId);
        }
        return computeHmac(key, signedContent);
    }

    public boolean verifyMacFromPeer(int peerId, byte[] signedContent, byte[] mac) {
        if (mac == null || mac.length != 32) {
            return false;
        }
        if (peerId < 0 || peerId >= n || peerId == selfId) {
            return false;
        }
        byte[] key = macKeys[peerId];
        if (key == null) {
            return false;
        }
        byte[] expected = computeHmac(key, signedContent);
        return java.security.MessageDigest.isEqual(expected, mac);
    }

    private static byte[] computeHmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data == null ? new byte[0] : data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    /** Marker written after RSA/threshold block in the multijvm key file. */
    public static byte[] fileSectionMagic() {
        return "DEPCHECDH1".getBytes(StandardCharsets.UTF_8);
    }
}
