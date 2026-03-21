# DepChain Stage 2 - Step 3 Progress

## Status

In progress.

### Build / toolchain

- **JDK 21** required (`maven.compiler.release` 21). Besu EVM 25.x aligns with Lab2 and avoids Java 17 vs bytecode mismatches.
- Dependencies: `evm` **25.6.0**, `besu-datatypes` **25.2.1** (see root `pom.xml`).

## Step 3.1 - Data model (started)

Implemented in this iteration:

- Added `src/main/java/depchain/blockchain/Transaction.java`
- Added `src/test/java/depchain/blockchain/TransactionTest.java`

Design choices:

- `Transaction` was placed in `depchain.blockchain` (yes, this is the right place for Step 3).
- Supports both:
  - native transfers (`data` empty)
  - contract calls (`data` non-empty)
  - contract deployment (`to` null/blank)
- Includes fields needed for Step 3:
  - `from`, `to`, `nonce`, `value`, `gasPrice`, `gasLimit`, `data`
- Includes fee-priority comparator:
  - `Transaction.FEE_PRIORITY` orders by `gasPrice` descending.

Current tests for the model:

- invalid gas fields are rejected
- deploy/call/transfer classification works
- sorting by fee priority works

## Notes

- Consensus payload migration (`String` -> `Block` with transactions) is not done in this sub-step.
- That integration remains for later (Step 4), after Step 3 execution pipeline is in place.

## Step 3.2 - Genesis model and loader (started)

Implemented in this iteration:

- Added `src/main/java/depchain/blockchain/Genesis.java`
- Added `src/main/java/depchain/blockchain/GenesisLoader.java`
- Added `src/main/resources/blockchain/genesis.json`
- Added `src/test/java/depchain/blockchain/GenesisLoaderTest.java`

Design choices:

- Genesis is represented as:
  - `blockHash`
  - `previousBlockHash`
  - list of `accounts` (address, balance, nonce)
  - list of initial `transactions`
- Initial transaction data supports:
  - hex (`0x...`) encoding
  - base64 encoding (fallback)

Current tests for genesis:

- resource loading works
- accounts are parsed correctly
- initial deployment transaction is parsed correctly (including `data`)

## Step 3.3 - Transaction execution and gas (first slice)

Implemented in this iteration:

- Added `src/main/java/depchain/blockchain/WorldState.java`
- Added `src/main/java/depchain/blockchain/TransactionExecutionResult.java`
- Added `src/main/java/depchain/blockchain/TransactionExecutor.java`
- Added `src/test/java/depchain/blockchain/TransactionExecutorTest.java`

What is covered now:

- Native transfer execution.
- Nonce validation.
- Fee charging in DepCoin before value transfer.
- Out-of-gas handling (fee charged, value transfer aborted).
- Fee formula from the statement:
  - `min(gas_price * gas_limit, gas_price * gas_used)`.

Current limitations (to be done in next slices):

- Contract call/deployment execution is still pending.
- Block append/persistence integration is still pending.

## Step 3.4 - Block append and JSON persistence (first slice)

Implemented in this iteration:

- Added `src/main/java/depchain/blockchain/ExecutedTransaction.java`
- Added `src/main/java/depchain/blockchain/LedgerBlock.java`
- Added `src/main/java/depchain/blockchain/BlockchainLedger.java`
- Added `src/main/java/depchain/blockchain/BlockJsonStore.java`
- Added `src/test/java/depchain/blockchain/BlockchainLedgerTest.java`
- Extended `WorldState` with state snapshot support.

What is covered now:

- Group pending transactions into a block.
- Order transactions by fee priority before execution.
- Execute transactions sequentially and attach execution results.
- Build block metadata:
  - `previousBlockHash`
  - `blockHash` (SHA-256 over deterministic block content)
  - `height`
  - `timestamp`
- Persist each block to JSON file (e.g., `1.json`, `2.json`, ...).
- Reload persisted blocks from JSON.

Current limitations:

- Contract call/deployment execution is still pending.
- Integration with consensus decision path is still pending (planned for Step 4).

## Step 3.5 - Contract deployment/call (intermediate slice)

Implemented in this iteration:

- Added `src/main/java/depchain/blockchain/ContractRuntimeRegistry.java`
- Extended `src/main/java/depchain/blockchain/TransactionExecutor.java`
- Added `src/test/java/depchain/blockchain/ContractExecutionTest.java`
- Extended `src/main/java/depchain/blockchain/TransactionExecutionResult.java` with optional created contract address.

What is covered now:

- Contract deployment transactions (`to == null`) now:
  - charge gas/fee
  - create deterministic contract address
  - register runtime bytecode in local registry
- Contract call transactions (`to != null` and `data` present) now:
  - charge gas/fee
  - validate contract existence in registry
  - support value transfer to contract account

Execution model (Besu + ledger):

- Deploy/call use `BesuEvmHelper` (Besu `SimpleWorld` + `EVMExecutor`). Runtime bytecode is registered in `ContractRuntimeRegistry`.
- **Native DepCoin** balances in `WorldState` are authoritative. After a successful contract **call**, `msg.value` is reflected by moving DepCoin from sender to contract in `WorldState` (the EVM world is used for opcode execution; ledger balances are updated explicitly for consistency).

Next steps (outside this doc): deeper consensus integration (`Block` with txs), richer gas accounting, persistence polish.