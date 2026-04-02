# DepChain — Stage 1

**Requirements:** **Java 21** (Besu EVM / bytecode), Maven 3.6+

---

## How to run the demos

### Main demo (5 separate JVMs, one command)

From the **project root**:

```bash
./run-multijvm-demo.sh
```

The script compiles the project, generates a key file if it does not exist, starts 4 members in the background, runs the client (you see the client output in the terminal: 3 appends with indices 0, 1, 2), then stops the members. No need to open multiple terminals.

### Manual 5 terminals

**Use Maven’s classpath** (Stage 2 pulls in web3j, Besu, etc.; plain `java -cp target/classes` will fail with `NoClassDefFoundError` for `org.web3j.crypto.Credentials`):

```bash
mvn -q compile
mvn -q exec:java -Dexec.args="genconfig"
```

That writes **`depchain-multijvm.keys`** at the project root (see below). Then open 4 terminals:

```bash
mvn -q exec:java -Dexec.args="member 0"   # and 1, 2, 3 in the other terminals
```

When all four show `Member X running`, in a 5th terminal:

```bash
mvn -q exec:java -Dexec.args="client"
```

or **`mvn -q exec:java -Dexec.args="interactive"`** for the IST / DepCoin REPL (`transfer` / `approve` / `transferFrom`, balances, native transfer).

**Alternative (same JVM, full classpath):** after `mvn compile`, run `mvn -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/cp.txt`, then `java -cp "target/classes:$(cat target/cp.txt)" depchain.Main client` (same pattern for `member` / `interactive`).

### Client shows `[client] timeout` / retries

The client talks to members over **UDP** on **30100–30103** and waits until **f+1** replicas reply after a block is decided. A timeout almost always means:

1. **Not all four members are running** — you need `member 0` … `member 3` at the same time (and HotStuff needs a quorum to commit).
2. **Members were started with `java -cp target/classes` only** — they can fail at startup (`NoClassDefFoundError`); use `mvn exec:java` or the full classpath as above.
3. **Another process already uses UDP 30200** (e.g. a second `client` / `interactive`) — only one listener on that port.

Check listening UDP ports (macOS/Linux): `lsof -nP -iUDP:30100` (repeat for 30101–30103). You should see a `java` process for each member.

### What is `depchain-multijvm.keys`?

Single binary blob **generated locally** (not committed). Layout:

1. **Quorum threshold key** (`GroupKey`) shared by all members.  
2. Per member: **RSA public+private** (APL / membership), **threshold `KeyShare`**, and in current builds a trailing section with **P‑256 (`secp256r1`)** key pairs (for link-MAC ECDH).  
3. The EC material enables **optional HMAC-SHA256 link authentication** with pairwise secrets derived via **ECDH** (`SHA-256(shared secret)` as MAC key). Older key files without that tail still work: members fall back to **RSA-signed** APL frames only.

Regenerate anytime with `depchain.Main genconfig` (overwrites the file).

### Mini demo script (interactive CLI, ~5 steps)

With four members running and a fifth terminal on `interactive`:

1. `balance ist 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` → expect a large balance (full IST supply to deployer in genesis bootstrap).  
2. `transfer 0x70997970C51812dc3A010C7d01b50e0d17dc79C8 10.0` → expect a non‑negative **chain index** (e.g. `0`).  
3. `use 1` then `balance ist 0x70997970C51812dc3A010C7d01b50e0d17dc79C8` → expect **1000** smallest units (10.00 IST with 2 decimals).  
4. `use 0` → `approve 0x70997970C51812dc3A010C7d01b50e0d17dc79C8 5.0` → index ≥ 0.  
5. `use 1` → `transferFrom 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266 0x70997970C51812dc3A010C7d01b50e0d17dc79C8 2.0` → Hardhat **account 1** (spender) moves IST from the owner above into **0x7099…**, within the approved allowance; re-check `balance ist` on both addresses.

The in-JVM **`demo`** path uses the same HMAC link mode as multi-JVM when EC material is present in the key file; legacy tests that omit `linkMac` keep RSA-only links.

### DEMO / evidências para professor

- **Caminho rápido:** `./run-multijvm-demo.sh` (4 membros + cliente, um comando).
- **Bizantinos / frontrunning:** correr a bateria Step 5 com Maven — lista de classes e comandos em **`STAGE2-STEP5-TESTS.md`** (secção “How to run the Step 5–focused suite”). Exemplo:
  ```bash
  mvn test -Dtest=ClientProtocolByzantineTest,VerifiedBatchLeaderInjectionTest,ByzantineClientIntegrationTest,ByzantineTest,ReplicaFailingTest,ISTCoinBesuExecutionTest
  ```
- **Nota:** cenários interactivos estilo consola (como noutros grupos) **não são obrigatórios**; os **testes** cobrem as propriedades relevantes do enunciado (incl. tolerância a réplicas/clientes bizantinos e mitigação de approval frontrunning no IST Coin).

---

## How to run the tests

Run **all tests**:

```bash
mvn clean test
```

Run **individual test classes** (useful to demonstrate specific behaviour):

| Command | What it exercises |
|--------|--------------------|
| `mvn test -Dtest=AuthenticatedPerfectLinkTest` | APL: send/receive, reject corrupted messages, deduplication. |
| `mvn test -Dtest=HotStuffIntegrationTest` | Consensus: 4 replicas decide blocks; view change after leader timeout. |
| `mvn test -Dtest=ByzantineTest` | One replica sends invalid votes; correct replicas still decide (Byzantine tolerance). |
| `mvn test -Dtest=IntrusiveNetworkTest` | Consensus with injectable message drop (retries / view change). |
| `mvn test -Dtest=ClientIntegrationTest` | Client broadcasts append to members; leader proposes; all decide and respond. |
| `mvn test -Dtest=MultiProcessConfigIntegrationTest` | Key-file flow: write keys, load 4 members from file, client append succeeds. |

---

## Stage 2 — IST Coin bytecode & genesis

- **`src/main/resources/contracts/ISTCoin.creation.hex`** — bytecode de **deploy** (Solidity `bytecode`).
- **`src/main/resources/contracts/ISTCoin.runtime.hex`** — bytecode **deployed** (`deployedBytecode`), usado na EVM após deploy.
- **`src/main/resources/blockchain/genesis.json`** — a transação inicial `to: null` usa o **creation** hex do IST (deploy real, não stub). Opcionalmente podes acrescentar **`contracts`**: lista de `{ "address", "runtimeHex", "storage": { "0x<slot>": "0x<value>", ... } }` para fixar bytecode + storage de contratos em génesis sem transação de deploy.

Para **recompilar** após alterar `ISTCoin.sol` (precisa de Node.js + `npx`):

```bash
chmod +x scripts/compile-ist-bytecode.sh
./scripts/compile-ist-bytecode.sh
```

Depois volta a gerar o campo `data` da transação de deploy no `genesis.json` (deve ser `0x` + conteúdo de `ISTCoin.creation.hex`), ou copia com:

```bash
python3 -c "import json; d=open('src/main/resources/contracts/ISTCoin.creation.hex').read().strip(); h=('0x'+d) if not d.startswith('0x') else d; g=json.load(open('src/main/resources/blockchain/genesis.json')); g['transactions'][0]['data']=h; json.dump(g, open('src/main/resources/blockchain/genesis.json','w'), indent=2)"
```

### Ordenação de transações num bloco (Stage 2 / Step 3)

Antes de executar, o custo real em gas (`gas_used`) ainda não existe. O enunciado define a fee como  
`min(gas_price × gas_limit, gas_price × gas_used)`.  
Por isso, para **ordenar** transações no mesmo bloco usamos o **limite superior** que o utilizador aceita pagar:

- **Chave principal:** `gas_price × gas_limit` (método `Transaction.maxFeeOffer()`) — **maior primeiro**.
- **Desempate:** `nonce` crescente (`BlockchainLedger.appendBlock`).

Isto aproxima “maior taxa / maior prioridade” de forma consistente com a fórmula de fee do PDF.

**Consenso (Step 4):** o líder recolhe até **64** pedidos verificados da mempool (`BlockchainMember`), ordena-os com `TransactionBatchOrder` (maior `maxFeeOffer` primeiro entre *cabeças* de remetentes; por remetente mantém-se `nonce` crescente) e propõe **um bloco** com vários itens `TxBatchPayload`. Assim, um bloco pode conter várias transações e respeita o enunciado dentro do bloco.

### Gas usado na execução (fee `min(gas_price × gas_limit, gas_price × gas_used)`)

- **Transferência nativa** (`to` sem código de contrato): `gas_used` = **21 000** (custo intrínseco; não passa pela EVM de contrato).
- **Deploy e chamadas a contrato:** o gas é **medido** com Besu (`EVMExecutor` + `GasCaptureTracer` em `BesuEvmHelper`); o valor usado para a fee é **limitado** pelo `gas_limit` da transação.
- Chamadas a endereços sem contrato registado cobram um intrínseco de falha (ver `TransactionExecutor`).

### Persistência de blocos (`BlockJsonStore`)

- Cada `LedgerBlock` gravado em JSON inclui o estado de contas (`balance` / `nonce`) e **`contractRuntimeHex`**: bytecode de **runtime** dos contratos deployados nesse bloco (para restaurar o `ContractRuntimeRegistry`).
- Para cumprir o requisito do enunciado de “world state” por bloco, também persistimos **`contractStorageHex`** para contratos suportados (atualmente **IST Coin**): slots de storage relevantes (balances e allowances) em hex 32 bytes.
- Restauro mínimo após `load`:
  - `ContractRuntimeRegistry.applyRuntimeHexSnapshot(block.getContractRuntimeHex())`
  - `BesuEvmHelper.applyCodesFromRegistry(registry)`
  - `BesuEvmHelper.applyContractStorageHexSnapshot(contractAddr, block.getContractStorageHex().get(contractAddr))`
