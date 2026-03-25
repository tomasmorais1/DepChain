package depchain.client;

import depchain.blockchain.Transaction;
import depchain.blockchain.TransactionCommandCodec;
import depchain.client.eth.TransactionEthHasher;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 2 Step 5: Byzantine client attempts at the wire layer — signature must bind to the exact
 * transaction body; replicas drop invalid requests before mempool / consensus.
 */
class ClientProtocolByzantineTest {

    private static final Credentials ALICE =
        Credentials.create("0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final Credentials BOB =
        Credentials.create("0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");

    @Test
    void verifyEthSignedTransaction_acceptsValidSignedTx() {
        Transaction tx = sampleTx(100);
        String encoded = TransactionCommandCodec.encode(tx);
        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        Sign.SignatureData sig = Sign.signMessage(hash, ALICE.getEcKeyPair(), false);
        ClientProtocol.Request req = new ClientProtocol.Request(1L, encoded, null, null, sig, ClientProtocol.TYPE_ETH_REQUEST);
        assertTrue(ClientProtocol.verifyEthSignedTransaction(req));
    }

    @Test
    void verifyEthSignedTransaction_rejectsWhenBodyDoesNotMatchSignature() {
        Transaction signedTx = sampleTx(100);
        Transaction tamperedBody = sampleTx(999);
        String encodedTampered = TransactionCommandCodec.encode(tamperedBody);
        byte[] hash = TransactionEthHasher.hashForSigning(signedTx);
        Sign.SignatureData sig = Sign.signMessage(hash, ALICE.getEcKeyPair(), false);
        ClientProtocol.Request req =
            new ClientProtocol.Request(1L, encodedTampered, null, null, sig, ClientProtocol.TYPE_ETH_REQUEST);
        assertFalse(ClientProtocol.verifyEthSignedTransaction(req));
    }

    @Test
    void verifyEthSignedTransaction_rejectsWhenSignerDoesNotMatchTxFrom() {
        Transaction tx = sampleTx(50);
        String encoded = TransactionCommandCodec.encode(tx);
        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        Sign.SignatureData sig = Sign.signMessage(hash, BOB.getEcKeyPair(), false);
        ClientProtocol.Request req = new ClientProtocol.Request(1L, encoded, null, null, sig, ClientProtocol.TYPE_ETH_REQUEST);
        assertFalse(ClientProtocol.verifyEthSignedTransaction(req));
    }

    @Test
    void verifyEthSignedTransaction_rejectsWrongRecoveredAddressForFromField() {
        Transaction tx = new Transaction(
            BOB.getAddress(),
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            1,
            1,
            21_000,
            null
        );
        String encoded = TransactionCommandCodec.encode(tx);
        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        Sign.SignatureData sig = Sign.signMessage(hash, ALICE.getEcKeyPair(), false);
        ClientProtocol.Request req = new ClientProtocol.Request(2L, encoded, null, null, sig, ClientProtocol.TYPE_ETH_REQUEST);
        assertFalse(ClientProtocol.verifyEthSignedTransaction(req));
    }

    private static Transaction sampleTx(long value) {
        return new Transaction(
            Numeric.prependHexPrefix(ALICE.getAddress()),
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            value,
            2,
            21_000,
            null
        );
    }
}
