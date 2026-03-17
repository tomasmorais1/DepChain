package threshsig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Secret key share for RSA (k,l) threshold scheme.
 */
public class KeyShare {
  private BigInteger secret;
  private BigInteger verifier;
  private BigInteger groupVerifier;
  private BigInteger n;
  private final BigInteger delta;
  private BigInteger signVal;
  private int id;

  private static SecureRandom random;
  private MessageDigest md;
  static {
    byte[] randSeed = new byte[20];
    (new Random()).nextBytes(randSeed);
    random = new SecureRandom(randSeed);
  }

  public KeyShare(final int id, final BigInteger secret, final BigInteger n, final BigInteger delta) {
    this.id = id;
    this.secret = secret;
    this.verifier = null;
    this.n = n;
    this.delta = delta;
    this.signVal = ThreshUtil.FOUR.multiply(delta).multiply(secret);
  }

  public int getId() { return id; }
  public BigInteger getSecret() { return secret; }

  public void setVerifiers(final BigInteger verifier, final BigInteger groupVerifier) {
    this.verifier = verifier;
    this.groupVerifier = groupVerifier;
  }

  public BigInteger getVerifier() { return verifier; }
  public BigInteger getSignVal() { return signVal; }

  /** Serialize for key file (contains secret material). */
  public byte[] encodeForFile() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(id);
    writeBigInt(d, secret);
    writeBigInt(d, n);
    writeBigInt(d, delta);
    writeBigInt(d, verifier);
    writeBigInt(d, groupVerifier);
    d.flush();
    return out.toByteArray();
  }

  /** Deserialize from key file. */
  public static KeyShare decodeFromFile(byte[] b) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
    int id = in.readInt();
    BigInteger secret = readBigInt(in);
    BigInteger n = readBigInt(in);
    BigInteger delta = readBigInt(in);
    BigInteger verifier = readBigInt(in);
    BigInteger groupVerifier = readBigInt(in);
    KeyShare ks = new KeyShare(id, secret, n, delta);
    ks.setVerifiers(verifier, groupVerifier);
    return ks;
  }

  private static void writeBigInt(DataOutputStream d, BigInteger x) throws IOException {
    byte[] b = x.toByteArray();
    d.writeInt(b.length);
    d.write(b);
  }

  private static BigInteger readBigInt(DataInputStream in) throws IOException {
    int len = in.readInt();
    byte[] b = new byte[len];
    in.readFully(b);
    return new BigInteger(b);
  }

  public SigShare sign(final byte[] b) {
    final BigInteger x = (new BigInteger(b)).mod(n);
    final int randbits = n.bitLength() + 3 * ThreshUtil.L1;
    final BigInteger r = (new BigInteger(randbits, random));
    final BigInteger vprime = groupVerifier.modPow(r, n);
    final BigInteger xtilde = x.modPow(ThreshUtil.FOUR.multiply(delta), n);
    final BigInteger xprime = xtilde.modPow(r, n);
    BigInteger c = null;
    BigInteger z = null;
    try {
      md = MessageDigest.getInstance("SHA");
      md.reset();
      md.update(groupVerifier.mod(n).toByteArray());
      md.update(xtilde.toByteArray());
      md.update(verifier.mod(n).toByteArray());
      md.update(x.modPow(signVal, n).modPow(ThreshUtil.TWO, n).toByteArray());
      md.update(vprime.toByteArray());
      md.update(xprime.toByteArray());
      c = new BigInteger(md.digest()).mod(n);
      z = (c.multiply(secret)).add(r);
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA not found", e);
    }
    Verifier ver = new Verifier(z, c, verifier, groupVerifier);
    return new SigShare(id, x.modPow(signVal, n), ver);
  }
}
