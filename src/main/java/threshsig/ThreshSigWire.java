package threshsig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/** Wire encoding/decoding for SigShare (for consensus messages). */
public final class ThreshSigWire {

  private ThreshSigWire() {}

  public static byte[] encodeSigShare(SigShare s) throws IOException {
    if (s == null || s.getSigVerifier() == null) return new byte[0];
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(s.getId());
    writeBigInt(d, s.getSig());
    Verifier v = s.getSigVerifier();
    writeBigInt(d, v.getZ());
    writeBigInt(d, v.getC());
    writeBigInt(d, v.getShareVerifier());
    writeBigInt(d, v.getGroupVerifier());
    d.flush();
    return out.toByteArray();
  }

  public static SigShare decodeSigShare(byte[] b) throws IOException {
    if (b == null || b.length == 0) return null;
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
    int id = in.readInt();
    BigInteger sig = readBigInt(in);
    BigInteger z = readBigInt(in);
    BigInteger c = readBigInt(in);
    BigInteger shareVerifier = readBigInt(in);
    BigInteger groupVerifier = readBigInt(in);
    Verifier ver = new Verifier(z, c, shareVerifier, groupVerifier);
    return new SigShare(id, sig, ver);
  }

  private static void writeBigInt(DataOutputStream d, BigInteger x) throws IOException {
    byte[] buf = x.toByteArray();
    d.writeInt(buf.length);
    d.write(buf);
  }

  private static BigInteger readBigInt(DataInputStream in) throws IOException {
    int len = in.readInt();
    byte[] buf = new byte[len];
    in.readFully(buf);
    return new BigInteger(buf);
  }
}
