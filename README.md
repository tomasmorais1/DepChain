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

Compile with `mvn compile`. Run once: `java -cp target/classes depchain.Main genconfig` (creates the key file). Then open 4 terminals and run `java -cp target/classes depchain.Main member 0`, `member 1`, `member 2`, `member 3` respectively. When all four show "Member X running", open a 5th terminal and run `java -cp target/classes depchain.Main client`.

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
- **`src/main/resources/blockchain/genesis.json`** — a transação inicial `to: null` usa o **creation** hex do IST (deploy real, não stub).

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
- O mundo Besu (`SimpleWorld`) **não** é serializado na íntegra (ex.: storage de mappings do IST). Para um nó “a frio”, a opção correta é **reexecutar** as transações desde o genesis ou alargar a persistência no Step 4.
- Restauro mínimo após `load`: `ContractRuntimeRegistry.applyRuntimeHexSnapshot(block.getContractRuntimeHex())` e `BesuEvmHelper.applyCodesFromRegistry(registry)`.
