# SEC-2526 Stage 2 (v2) — cruzamento com o código (`ENUNCIADO-CLIENTE-ETH.md`)

**Fonte:** [`SEC-2526 project - stage 2 - v2.pdf`](./SEC-2526%20project%20-%20stage%202%20-%20v2.pdf) (Highly Dependable Systems / DepChain Stage 2).

Este documento cruza o **PDF** com a implementação actual, com ênfase em **cliente, contas, não-repúdio e “estilo Ethereum”** (assinaturas). Outros ficheiros do repo (`README.md`, `STAGE2-PLANO-STEP3-vs-STEP4.md`, `STAGE2-STEP4-IMPLEMENTATION-PLAN.md`) cobrem génesis, gas, consenso e passos 3–4.

---

## 0. Registo de alterações (cliente ETH + génesis de teste)

Iteração que acrescenta **assinatura de transacções estilo Ethereum** (Keccak-256 + ECDSA secp256k1 + verificação por endereço recuperado) e alinha contas de teste com chaves públicas conhecidas. Resume o que foi **actualizado** no código em relação ao fluxo anterior (consenso Step 4 + cliente maioritariamente RSA sobre o mesmo JSON de transação).

| Ficheiro / pasta | Alteração |
|------------------|-----------|
| `pom.xml` | Dependência `org.web3j:crypto` (hash Keccak, `Sign`, `Credentials`, `Keys`). |  
| `src/main/java/depchain/client/eth/TransactionEthHasher.java` | **Novo:** digest canónico `DepChainTxV1` + campos da `Transaction`; Keccak-256 para assinar. |
| `src/main/java/depchain/client/ClientProtocol.java` | Novo wire **`TYPE_ETH_REQUEST` (2)**; `encodeEthRequest` / parse; `verifyEthSignedTransaction`; `verifyRequest` escolhe ETH vs RSA. |
| `src/main/java/depchain/client/DepChainClient.java` | `Credentials` opcional; `appendTransaction` assina o hash com a chave ETH e envia pedido tipo 2; RSA mantém-se em `append(String)`. |
| `src/main/java/depchain/blockchain/BlockchainMember.java` | Exige comando JSON de `Transaction` válido **só** para pedidos ETH; pedidos RSA (tipo 0) não são forçados a ser decode de `Transaction`. |
| `src/main/java/depchain/Main.java` | `Main client` usa `Credentials` (chave Hardhat #0) e endereços coerentes com o génesis. |
| `src/main/java/depchain/demo/Demo.java` | Envia `TransactionCommandCodec.encode(tx)` em `append` (RSA), em vez de strings livres, para o consenso executar transacções válidas. |
| `src/main/resources/blockchain/genesis.json` | Contas **Hardhat** (`0xf39F…`, `0x7099…`) em vez de placeholders `0xaaaa…` / `0xbbbb…`; deploy mantém **creation bytecode** do IST. |
| `src/test/java/...` | Vários testes: endereços Hardhat; integrações com `Credentials` + `appendTransaction`; `ISTCoinBesuExecutionTest` com os mesmos endereços (hex). |
| `ENUNCIADO-CLIENTE-ETH.md` | Este documento (cruzamento PDF ↔ código + registo de updates). |

**Não listado acima:** ficheiros sob `build/`, `target/` e JSON de consenso gerados em runtime — artefactos locais, não parte da especificação.

---

## 1. O que o PDF diz (citações de requisitos relevantes)

### 1.1 Design Requirements — posse da chave e não-repúdio

O enunciado exige que as transferências em DepCoin só ocorram se o cliente **possuir a chave privada da conta de débito** e que o sistema garanta **não-repúdio**:

> *“A client of the system can perform DepCoin transfers between a pair of accounts **provided it owns the corresponding private key of the crediting account** (the one from which money is transferred).”*

> *“the system should guarantee the **non-repudiation of all operations** issued on an account”*

**Implementação:** pedidos **tipo ETH** (`ClientProtocol.TYPE_ETH_REQUEST`): o cliente assina um digest (**Keccak-256** de `TransactionEthHasher.hashForSigning(Transaction)`), com **ECDSA secp256k1** (web3j). As réplicas verificam com **recuperação de chave pública → endereço** e exigem que o endereço recuperado coincida com o campo `from` da transação (`ClientProtocol.verifyEthSignedTransaction`). Isto liga criptograficamente a operação à conta EOA, no espírito “Ethereum-like” (hash + curva secp256k1 + endereço derivado).

### 1.2 Clientes bizantinos e chaves honestas

> *“clients may be Byzantine, **they cannot subvert the keys of honest clients**”*

**Implementação:** um cliente bizantino não pode fazer com que o consenso aceite uma transação com `from` igual a uma conta honesta **sem** a respectiva chave privada: a verificação ETH falha. (O líder bizantino também não consegue injectar comandos assinados por outrem se `isVerifiedClientBlock` só aceitar `requestId → comando` pré-verificado.)

### 1.3 Modelo de transacção “não precisa de ser Ethereum”

> *“**The concept of transactions does not have to be equivalent to the model of Ethereum.** You are free to design your own transaction object, however, it must enable the execution of smart contracts functions and the transfer of native cryptocurrency.”*

**Conclusão para assinaturas:** o PDF **não obriga** a assinatura de transacções no formato **RLP + EIP-155** da Ethereum Yellow Paper. Um digest canónico próprio (`DepChainTxV1` + campos em `DataOutputStream`) **é compatível** com o texto, desde que o sistema imponha posse de chave e não-repúdio como acima.

### 1.4 Outros requisitos do PDF (ponteiro)

| Requisito (resumo do PDF) | Onde ver no projecto |
|---------------------------|---------------------|
| ERC-20 “IST Coin”, 2 decimais, 100M unidades; mitigação a **Approval Frontrunning** | `ISTCoin.sol`, testes Besu |
| **Fee** `min(gas_price × gas_limit, gas_price × gas_used)`; gas_used pela EVM | `TransactionExecutor`, `BesuEvmHelper`, `README` |
| Blocos com várias txs; **ordem por taxas** (maior fee primeiro) | `BlockchainLedger`, `Transaction.maxFeeOffer()` |
| Génesis JSON com estado e deploy do contrato | `genesis.json`, `GenesisLoader` |
| **Step 4:** consenso decide ordem e liga ao pipeline | `BlockchainMember`, `HotStuffReplica`, `TxBatchPayload` |
| **Step 5:** testes a comportamento bizantino (incl. frontrunning) | Suite `src/test/...`; confirmar cobertura vs PDF antes da entrega |

---

## 2. Está “100%” conforme o PDF?

- **Sobre o texto literal do PDF** para **posse de chave + não-repúdio** num modelo inspirado em Ethereum: a abordagem **Keccak-256 + ECDSA secp256k1 + verificação por endereço recuperado** está **alinhada** com as frases citadas em §1.1 e com §1.3 (transacção não precisa de ser igual à Ethereum).
- **“100% Stage 2 completo”** (incl. todos os passos, testes Step 5, relatório LNCS, etc.) **não** é afirmável só com este ficheiro — depende de **todos** os requisitos (génesis, gas, IST, consenso, demos no README, etc.) e da revisão final do grupo.

---

## 3. Diferenças em relação a uma wallet Ethereum “real” (mainnet)

Isto **não** contradiz o PDF (que não exige equivalência com o modelo Ethereum), mas é útil para perceber interoperabilidade:

1. **Sem RLP / EIP-155 no digest** — Não se assina `Keccak256(RLP(tx))` com chain id; assina-se o hash DepChain (`DepChainTxV1` + campos).
2. **O comando em claro ainda inclui `from` no JSON** — A réplica exige consistência entre `from` e o endereço recuperado da assinatura (reforço de não-repúdio no vosso desenho).
3. **Replay entre redes** — Não há `chainId` no digest como no EIP-155; o prefixo `DepChainTxV1` separa de outros usos da mesma chave fora deste protocolo, mas não substitui uma rede Ethereum pública.

---

## 4. Modo RSA legado (tipo 0) vs modo ETH (tipo 2)

O **PDF** não menciona RSA no protocolo de cliente; descreve contas ao estilo Ethereum (EOA com par de chaves).

| Modo | Uso | Nota face ao enunciado |
|------|-----|-------------------------|
| **Tipo 2 (ETH)** | `DepChainClient.appendTransaction` — assinatura **secp256k1** sobre o hash da transação | Alinhado com “private key of the crediting account” e não-repúdio “à maneira Ethereum”. |
| **Tipo 0 (RSA)** | `append(String)` — assinatura **RSA** sobre `requestId \|\| payload` (ex. demo com JSON de transação) | **Não** reproduz o modelo de chaves Ethereum (curva secp256k1); pode servir como caminho legado/Demo se o comando for ainda assim uma transação válida. Para **demonstrar** só o que o PDF pede em termos de conta/chave, preferir **tipo 2**. |

---

## 5. Checklist rápida (PDF ↔ implementação)

- [x] Transferências / operações exigem capacidade de provar posse da chave da conta de débito (via assinatura verificável).  
- [x] Não-repúdio forte para o comando de transação assinado no modo ETH (hash + ECDSA + recuperação).  
- [x] Transacção **não** obrigada a ser igual ao modelo Ethereum (texto explícito do PDF).  
- [x] Não é exigido pelo PDF usar RLP/EIP-155 para assinar — ver §3 apenas como diferença face a MetaMask/mainnet.  
- [ ] **Step 5** do PDF (bateria contra bizantinos, incl. frontrunning em cenário de sistema): validar testes e demos contra o que o professor espera.  
- [ ] **RSA** ainda presente: aceitável para compatibilidade interna; não substitui o argumento “EOA estilo Ethereum” do PDF — usar **ETH** para esse argumento.

---

## 6. Ficheiros principais (modo ETH)

- `src/main/java/depchain/client/eth/TransactionEthHasher.java` — digest Keccak-256 para assinar.  
- `src/main/java/depchain/client/ClientProtocol.java` — wire tipo 2, `verifyEthSignedTransaction`.  
- `src/main/java/depchain/client/DepChainClient.java` — `appendTransaction` + `Credentials` web3j.  
- `src/main/java/depchain/blockchain/BlockchainMember.java` — verificação antes de aceitar / propor.

*Última revisão: alinhada ao conteúdo textual de `SEC-2526 project - stage 2 - v2.pdf`; §0 regista alterações de código da iteração “cliente ETH”.*
