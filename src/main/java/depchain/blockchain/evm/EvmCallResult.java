package depchain.blockchain.evm;

import org.apache.tuweni.bytes.Bytes;

/** Result of a metered EVM contract call. */
public record EvmCallResult(Bytes output, long gasUsed) {}
