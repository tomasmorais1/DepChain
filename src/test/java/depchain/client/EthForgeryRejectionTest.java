package depchain.client;

import depchain.blockchain.Transaction;
import depchain.blockchain.TransactionCommandCodec;
import depchain.client.eth.TransactionEthHasher;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extra Byzantine-client tests focused on the exact Stage-2 threat model:
 * a request must be signed by the private key that controls tx.from, and the
 * signature must bind to the exact transaction body.
 */
class EthForgeryRejectionTest {

    private static final Credentials ALICE =
        Credentials.create("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final Credentials BOB =
        Credentials.create("0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");

    @Test
    void encodedWireRequest_roundTripsAndVerifies() {
        Transaction tx = sampleTx(0, 25, 3);
        ClientProtocol.Request parsed = roundTripSignedRequest(11L, tx, ALICE);
        assertNotNull(parsed);
        assertTrue(ClientProtocol.verifyRequest(parsed));
        assertTrue(ClientProtocol.verifyEthSignedTransaction(parsed));
    }

    @Test
    void forgedByzantineClient_cannotReuseSignatureForTamperedBody() {
        Transaction honest = sampleTx(0, 25, 3);
        Transaction tampered = sampleTx(0, 10_000, 3);

        byte[] honestHash = TransactionEthHasher.hashForSigning(honest);
        Sign.SignatureData sig = Sign.signMessage(honestHash, ALICE.getEcKeyPair(), false);

        String encodedTampered = TransactionCommandCodec.encode(tampered);
        byte[] wire = ClientProtocol.encodeEthRequest(12L, encodedTampered, sig);
        ClientProtocol.Request parsed = ClientProtocol.parseRequest(wire);

        assertNotNull(parsed);
        assertFalse(ClientProtocol.verifyRequest(parsed));
        assertFalse(ClientProtocol.verifyEthSignedTransaction(parsed));
    }

    @Test
    void signerMismatch_rejectedWhenRecoveredAddressDiffersFromTxFrom() {
        Transaction tx = sampleTx(1, 50, 2);

        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        Sign.SignatureData sig = Sign.signMessage(hash, BOB.getEcKeyPair(), false);

        byte[] wire = ClientProtocol.encodeEthRequest(13L, TransactionCommandCodec.encode(tx), sig);
        ClientProtocol.Request parsed = ClientProtocol.parseRequest(wire);

        assertNotNull(parsed);
        assertFalse(ClientProtocol.verifyRequest(parsed));
        assertFalse(ClientProtocol.verifyEthSignedTransaction(parsed));
    }

    private static ClientProtocol.Request roundTripSignedRequest(long requestId, Transaction tx, Credentials signer) {
        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        Sign.SignatureData sig = Sign.signMessage(hash, signer.getEcKeyPair(), false);
        byte[] wire = ClientProtocol.encodeEthRequest(requestId, TransactionCommandCodec.encode(tx), sig);
        return ClientProtocol.parseRequest(wire);
    }

    private static Transaction sampleTx(long nonce, long value, long gasPrice) {
        return new Transaction(
            Numeric.prependHexPrefix(ALICE.getAddress()),
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            nonce,
            value,
            gasPrice,
            21_000,
            null
        );
    }
}
