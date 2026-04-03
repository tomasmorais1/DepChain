# DepChain — Stage 2

**Requirements:** **Java 21** (Besu EVM / bytecode), Maven 3.6+

---

## Demos

### 1. Automatic multi-JVM demo

From the **project root**:

```bash
./run-multijvm-demo.sh
```

It runs `mvn compile`, builds a runtime classpath, creates **`depchain-multijvm.keys`** if missing, starts **four** member processes in the background (consensus UDP **30000–30003**, client-facing UDP **30100–30103**), then runs the **batch client** in the foreground. That client submits **three** fixed native DepCoin transfers (Hardhat account 0 → account 1, nonces 0–2) and prints the decided chain indices. Finally it stops the members. Logs: `member-0.log` … `member-3.log`.


### 2. Manual multi-JVM (five terminals)

You must use **Maven’s full classpath** (Stage 2 depends on web3j, Besu, etc.). Plain `java -cp target/classes …` alone will fail with `NoClassDefFoundError` (e.g. `org.web3j.crypto.Credentials`).

**Once — generate keys** (writes **`depchain-multijvm.keys`** at the repo root):

```bash
cd /path/to/DepChain
mvn -version   # must show Java 21.x
mvn -q clean compile
mvn -q exec:java -Dexec.args="genconfig"
```

**Four terminals — one member each:**

```bash
mvn -q exec:java -Dexec.args="member 0"
mvn -q exec:java -Dexec.args="member 1"
mvn -q exec:java -Dexec.args="member 2"
mvn -q exec:java -Dexec.args="member 3"
```

Wait until all show `Member X running (consensus port …, client port …)`.

**Fifth terminal — choose one:**

| Mode | Command | Purpose |
|------|---------|---------|
| **Batch client** | `mvn -q exec:java -Dexec.args="client"` | Sends the same 3 scripted DepCoin txs as the shell script; binds UDP **30200**. |
| **Interactive REPL** | `mvn -q exec:java -Dexec.args="interactive"` | IST / DepCoin commands (see next section); binds UDP **30217** by default. |

## Interactive REPL (`interactive`)

Use this after the **four members** are up.

**Startup:** waits ~4s, prints the derived **IST contract address** (from genesis deployer + nonce 0), then accepts input.

**Signing identity:** genesis includes two Hardhat-style EOAs. Use **`use 0`** or **`use 1`** to sign as that account (new `DepChainClient` with the matching private key). Only **0** and **1** are valid.

**Commands:**

| Command | Meaning |
|---------|---------|
| `help` | Short built-in help. |
| `use 0` / `use 1` | Switch active signer (account 0 or 1). |
| `balance dep <addr>` | Native **DepCoin** balance (integer, genesis units). |
| `balance ist <addr>` | **IST** token balance (smallest units + human IST with 2 decimals). |
| `native <to> <whole>` | Native DepCoin transfer of **whole** units from the current signer. |
| `transfer <to> <amount>` | IST `transfer` — `amount` like `10.0` (2 decimals). |
| `approve <spender> <amount>` | IST `approve` (after mitigations: non-zero → non-zero may require allowance reset; see tests). |
| `transferFrom <from> <to> <amount>` | IST `transferFrom` — current signer must be the **approved spender** (typical flow: `use 1` to act as spender). |
| `quit` / `exit` | Leave the REPL. |

**Suggested walkthrough (with members running):**

Demo accounts: **0** = `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266`, **1** = `0x70997970C51812dc3A010C7d01b50e0d17dc79C8`.

1. `use 0` → `balance dep 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` (positive; pays gas) → `balance ist 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` (large; IST minted to deployer in genesis).  
2. `transfer 0x70997970C51812dc3A010C7d01b50e0d17dc79C8 10.0` → expect `IST transfer -> index N` with \(N \ge 0\).  
3. `balance ist 0x7099…79C8` → expect **10.00 IST** (1000 smallest units).  
4. `use 0` → `approve 0x7099…79C8 5.0` → allowance for account 1 as spender.  
5. `use 1` → `transferFrom 0xf39F…92266 0x7099…79C8 2.0` → spender pulls IST; re-check `balance ist` on both addresses as needed.

---

## Tests

### Full suite

```bash
mvn clean test
```

### Stage 2
These classes focus on invalid/forged client traffic, leader/mempool integration, faulty replicas, and Besu-side IST `approve` rules:

```bash
mvn test -Dtest=ClientProtocolByzantineTest,VerifiedBatchLeaderInjectionTest,ByzantineClientIntegrationTest,ByzantineTest,ReplicaFailingTest,ISTCoinBesuExecutionTest
```

### Individual test classes (examples)

| Command | What it exercises |
|--------|--------------------|
| `mvn test -Dtest=AuthenticatedPerfectLinkTest` | APL: send/receive, reject corrupted messages, deduplication. |
| `mvn test -Dtest=HotStuffIntegrationTest` | Consensus: 4 replicas decide; view change after leader timeout. |
| `mvn test -Dtest=ByzantineTest` | One replica sends bad votes; others still decide. |
| `mvn test -Dtest=IntrusiveNetworkTest` | Injectable message loss / retries / view change. |
| `mvn test -Dtest=ClientIntegrationTest` | Client broadcast → leader proposes → decide → client response. |
| `mvn test -Dtest=MultiProcessConfigIntegrationTest` | Key file → four members → client append. |
