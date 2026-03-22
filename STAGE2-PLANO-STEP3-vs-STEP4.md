# Stage 2 — Plano: o que falta, Step 3 vs Step 4/5

Este documento junta:
- os gaps que já foram identificados na revisão do enunciado;
- o que o teu colega descreveu (consenso ainda em “strings”, gas fixo, assinaturas em `Transaction`);
- e explica **para que serve** o material do lab2 (`lab02_Smart_Contracts.pdf` + `lab2-with-maven`).

---

## 1. O que o enunciado chama a cada step (resumo)

| Step | O que é (PDF) |
|------|----------------|
| **1** | Garantir requisitos do Stage 1 (consenso, links, cliente, etc.). |
| **2** | ERC-20 em Solidity (IST Coin, frontrunning-resistant) + testes com Besu (lab2). |
| **3** | Genesis, transacções, gas, execução (EVM), append e **persistência** de blocos (sem obrigatoriamente ligar ao consenso ainda). |
| **4** | Ligar o **HotStuff** ao pipeline de execução: ordem de transacções decidida pelo protocolo e blocos como unidade de consenso. |
| **5** | Bateria de testes contra Bizantinos (incl. approval frontrunning). |

**Conclusão:** integrar `LedgerBlock` / `Transaction` com `Block` / `BlockchainService` / `HotStuffReplica` é **Step 4**, não Step 3.

---

## 2. Para que serve o lab2 (PDF + pasta Maven)

### `lab02_Smart_Contracts.pdf`

- Contexto: contas EOA vs contract, modelo de execução EVM, gas por instrução, Solidity, deploy (bytecode), ERC-20.
- **Não é código executável** — é base teórica para perceberes o que o Stage 2 pede (DepCoin nativo vs token ERC-20, gas, etc.).

### `lab2-with-maven` (HelloWorld / Storage)

- É o **boilerplate Maven** recomendado no enunciado: dependências **Besu EVM** + Tuweni + Gson, `EVMExecutor.evm(CANCUN)`, `executor.code(...)`, `executor.callData(...)`, `executor.execute()`.
- O `HelloWorld/src/main/java/Main.java` mostra o padrão que já replicastes em `ISTCoinBesuExecutionTest` e em `BesuEvmHelper`: carregar bytecode hex, correr uma chamada, (opcional) `StandardJsonTracer` para debug.

**Uso prático para o grupo:** quando precisarem de **gas “real”** ou de inspecionar execução, o lab2 é o ponto de partida para ver como plugar tracer / ler resultado da execução — a integração concreta no DepChain é código vosso em cima da API Besu.

---

## 3. Estado atual (síntese honesta)

### Já existe no repo (alinhado com Step 2 / grande parte do Step 3)

- **Step 2:** `ISTCoin.sol`, bytecode runtime em `ISTCoin.runtime.hex`, teste `ISTCoinBesuExecutionTest` (approve com reset).
- **Step 3 (pipeline local):** `Transaction`, `WorldState`, `Genesis` + `GenesisLoader` + `genesis.json`, `TransactionExecutor`, `BlockchainLedger`, `LedgerBlock`, `BlockJsonStore`, testes (`GenesisLoaderTest`, `TransactionExecutorTest`, `BlockchainLedgerTest`, `ContractExecutionTest`).

### O teu colega está certo nestes pontos

| Afirmação | Veredito |
|-----------|----------|
| `Block` (consenso) / `BlockchainService` ainda no modelo Stage 1 (payload = bytes / strings) | **Sim** — isso é **Step 4** (ligar consenso ao ledger). |
| Gas “real” da EVM: executor usa custos **fixos** por tipo | **Sim** — gap de **Step 3** (ou refinamento forte antes do Step 4). |
| Assinaturas no modelo `Transaction` (vs só no cliente Stage 1) | **Mistura:** o enunciado Stage 2 fala em **clientes Bizantinos** e não-repúdio; isso encaixa em **Step 4/5** quando as transacções forem propostas no consenso. Para **ledger local** (Step 3), pode ser opcional se só testares com contas geradas em testes. |
| Step 3 = modelo + genesis + executor + ledger + JSON + testes **sem** HotStuff | **Sim** — coerente com o PDF (Step 4 é a ligação explícita). |

### Gaps adicionais (revisão enunciado + código)

| Gap | Step (típico) | Notas |
|-----|----------------|-------|
| `genesis.json` com deploy **placeholder** (`0x60006000`) em vez do bytecode real do **ISTCoin** | **3** | **Feito:** `genesis.json` usa creation bytecode; `ISTCoin.creation.hex` / `ISTCoin.runtime.hex`; deploy em `TransactionExecutor` + `IstCoinBytecode` + seed de supply. |
| Ordenação “maior taxa primeiro” | **3** | **Feito:** `Transaction.maxFeeOffer()` = `gasPrice * gasLimit`; `FEE_PRIORITY` ordena por isto (desc), depois `nonce`; README documenta. |
| Estado de conta **contract** (code + storage) no snapshot persistido | **3** (mínimo exigível pelo texto) | Hoje o snapshot é sobretudo balance/nonce no `WorldState`; contratos usam `BesuEvmHelper` + registry — falta modelo/persistência completa se o professor for estrito. |
| `gas_used` determinado pela **EVM**, não constantes | **3** | Precisas expor gas usado pelo `EVMExecutor` (ou tracer) e aplicar a fórmula `min(gas_price*gas_limit, gas_price*gas_used)`. |

---

## 4. O que fazer **agora** (só Step 3 e “atrasos” do Step 2/3)

Ordem sugerida (incremental):

1. **Genesis real do IST** — **implementado**  
   - `genesis.json` contém o **creation bytecode** (`0x` + `ISTCoin.creation.hex`).  
   - `TransactionExecutor` reconhece `IstCoinBytecode.isKnownCreationBytecode`, instala **runtime** (`ISTCoin.runtime.hex`), regista no `ContractRuntimeRegistry`, e faz **seed** do `_balances[deployer]` na EVM (efeito do `constructor` do IST).  
   - O endereço do contrato continua **derivado** (`deriveContractAddress`) — não é CREATE Ethereum; está documentado no README.  
   - Script: `scripts/compile-ist-bytecode.sh`; teste: `GenesisIstDeployTest`.

2. **Fee ordering** — **implementado**  
   - `Transaction.maxFeeOffer()` = `gas_price × gas_limit` (com overflow → `Long.MAX_VALUE`).  
   - `BlockchainLedger` ordena por `FEE_PRIORITY` (max fee offer desc) + `nonce` asc.  
   - Documentado no README e javadoc de `Transaction` / `BlockchainLedger`.

3. **Gas usado pela execução Besu** — **implementado**  
   - **Transferências nativas:** `gas_used` = **21 000** (intrínseco Ethereum; sem execução de opcode na EVM).  
   - **Deploy / chamadas a contrato:** `BesuEvmHelper.callMetered` e `measureContractCreationGas` executam com um `GasCaptureTracer` e usam o **gas consumido** reportado pelo tracer (limitado a `gas_limit` para efeitos de fee).  
   - Falhas antecipadas (ex. contrato desconhecido) usam um intrínseco de chamada falhada documentado em `TransactionExecutor`.  
   - Ficheiros: `GasCaptureTracer.java`, `EvmCallResult.java`, lógica em `BesuEvmHelper` + `TransactionExecutor`.

4. **Persistência / estado** — **implementado (mínimo Step 3)**  
   - Cada `LedgerBlock` persistido em JSON inclui `contractRuntimeHex`: mapa **endereço → bytecode de runtime** (hex sem `0x`), espelhando o `ContractRuntimeRegistry` após as txs do bloco.  
   - Após `BlockJsonStore#load`, usar `ContractRuntimeRegistry#applyRuntimeHexSnapshot` e `BesuEvmHelper#applyCodesFromRegistry` para voltar a ter código executável na EVM.  
   - **Não** persistimos o storage completo do `SimpleWorld` Besu (slots IST, etc.); recuperação total = reexecutar a cadeia **ou** extensões no Step 4.

5. **`stage2_step3_progress.md`** — atualizado com gas Besu, persistência de bytecode em `LedgerBlock`, e notas Step 4.

---

## 5. O que **deixar para Step 4** (não misturar com “fechar Step 3”)

- Payload do `depchain.consensus.Block` com **lista de `Transaction`** (ou hash + referência).
- `BlockchainMember` / `BlockchainService` a aplicar `BlockchainLedger` no **upcall** DECIDE.
- **Assinatura de transacções** no formato que vai para o consenso + validação nas réplicas (Bizantino cliente).
- Threshold / chaves já existentes em `threshsig/` se forem usadas no protocolo — alinhar com o desenho do grupo.

---

## 6. O que é **Step 5**

- Testes de ataque (incl. frontrunning de approval em cenário realista), Bizantinos clientes + servidores, etc. — **depois** do pipeline + consenso estarem ligados.

---

## 7. Checklist rápida para dizeres na reunião

- **Step 2:** Contrato IST + teste Besu — **feito** (com ressalva: manter JDK 21 alinhado ao `pom.xml`).  
- **Step 3:** Modelo + execução + ledger + JSON — **feito** no âmbito do plano (genesis IST, fee ordering, gas Besu, bytecode de contrato nos blocos JSON; storage EVM completo fica para evolução / Step 4).  
- **Step 4:** Integração consenso ↔ blocos de transacções — **ainda não** (como o colega disse).  
- **Step 5:** Testes Bizantinos amplos — **esperar**.

---

*Última atualização: gerado para alinhar o grupo com o PDF Stage 2 v2 e o código atual do repositório DepChain.*
