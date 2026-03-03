# Guia completo do projeto DepChain (Stage 1)

Este documento explica o projeto do zero: o que é o enunciado, os conceitos base, a arquitetura e cada ficheiro que foi criado, com o raciocínio por detrás das decisões.

**Índice**
1. [O que é o enunciado](#1-o-que-é-o-enunciado-em-poucas-palavras)
2. [Conceitos base (BFT, HotStuff, abstrações de rede)](#2-conceitos-base-para-enquadrar-o-código)
3. [Visão geral da arquitetura](#3-visão-geral-da-arquitetura)
4. [Estrutura de pastas](#4-estrutura-de-pastas-e-ficheiros)
5. [Config: NodeAddress, Membership](#5-configuração-config)
6. [Transporte: UdpTransport, FairLossLink](#6-transporte-transport)
7. [Links: APLMessage, AuthenticatedPerfectLink](#7-links-autenticados-links)
8. [Consenso: Block, Phase, QC, mensagens, rede, HotStuffReplica](#8-consenso-hotstuff-consensus)
9. [Blockchain: BlockchainService, BlockchainMember](#9-blockchain-blockchain)
10. [Cliente: ClientProtocol, DepChainClient](#10-cliente-client)
11. [Demo e testes](#11-demo-e-testes)
12. [Fluxo completo de um append](#12-fluxo-completo-de-um-appendstring-para-fixar-ideias)
13. [O que não está no projeto](#13-o-que-não-está-no-projeto-e-está-correto)

---

## 1. O que é o enunciado (em poucas palavras)

O enunciado pede um sistema chamado **DepChain** (Dependable Chain): uma blockchain simplificada, **permissionada** (só nós autorizados participam), com garantias de **dependabilidade** (tolerância a falhas).

- **Stage 1** (este projeto): focar só na **camada de consenso** — fazer com que vários nós concordem na mesma sequência de “blocos” (aqui, strings a appendar), mesmo com falhas de rede e com alguns nós maliciosos (Bizantinos).
- O algoritmo a usar é o **Basic HotStuff** (Algorithm 2 do paper), **sem** a parte de “chaining” (Section 5).
- O sistema tem duas partes:
  - **Client library**: a aplicação do utilizador chama `append(string)`; a library envia o pedido ao sistema e espera confirmação.
  - **Blockchain members**: os nós que correm o consenso e mantêm o “livro de registos” (por agora, um array de strings em memória).
- A rede é **não fiável** (perdas, atrasos, duplicados) e **não** se pode usar TLS; a comunicação base é **UDP**, e em cima construímos abstrações (links) como na disciplina.
- Há **testes intrusivos**: formas de simular perda de mensagens e nós Bizantinos para validar o comportamento.

Resumindo: implementar Basic HotStuff em Java sobre UDP, com client library que faz append de strings e members que executam o consenso e mantêm um array de strings; tudo conforme os 6 steps sugeridos no enunciado.

---

## 2. Conceitos base (para enquadrar o código)

### 2.1 Consenso BFT (Byzantine Fault Tolerant)

- Vários nós precisam de **acordar** no mesmo valor (por exemplo, o próximo bloco).
- Alguns nós podem estar **em falha** (crash) ou **maliciosos** (Bizantinos: enviam mensagens erradas).
- **BFT** significa: mesmo com até **f** nós Bizantinos, os nós corretos concordam no mesmo valor e esse valor é “válido” (não inventado por um atacante).
- Para isso é preciso **n ≥ 3f + 1** nós no total. Por exemplo: 4 nós permitem f=1 Bizantino; 7 nós permitem f=2.

### 2.2 HotStuff (resumo muito breve)

- É um algoritmo de consenso **liderado**: em cada “view” há um **leader** que propõe um bloco.
- O bloco passa por **4 fases**: PREPARE → PRE-COMMIT → COMMIT → **DECIDE**.
- Em cada fase, o leader envia uma proposta (bloco + certificado da fase anterior); as réplicas **votam** (assinam); o leader junta **2f+1** votos num **Quorum Certificate (QC)** e passa à fase seguinte.
- Na **4.ª fase (DECIDE)** o leader envia uma mensagem **DECIDE** com o commit QC; só quando uma réplica **recebe** essa mensagem é que executa o bloco (upcall) e avança para a próxima view.
- Para **segurança**: cada réplica faz “lock” no bloco quando pre-commit; só vota em propostas compatíveis com esse lock.
- Para **liveness**: há um **pacemaker** (timeout); se a view não avançar, as réplicas mudam de view (novo leader).

### 2.3 Abstrações de rede (da disciplina)

- **Fair Loss Link**: a mensagem pode perder-se, mas se enviarmos várias vezes, “eventualmente” uma chega (não garante entrega única).
- **Perfect Link**: entrega exatamente uma vez, sem duplicados nem mensagens inventadas.
- **Authenticated Perfect Link (APL)**: Perfect Link + **autenticação**: sabemos quem enviou e que o conteúdo não foi alterado (assinaturas). No nosso projeto a **deduplicação** é feita **depois** de verificar a assinatura, para que um Bizantino não consiga fazer descartar a mensagem legítima com um ID falsificado.

Com estes conceitos em mente, o código fica mais fácil de seguir.

---

## 3. Visão geral da arquitetura

```
[ Aplicação ]  -->  append(string)  -->  [ DepChainClient ]
                                              |
                                              | UDP (broadcast para todos os members)
                                              v
[ Blockchain Member 0 ]  [ Member 1 ]  ...  [ Member n ]
       |                      |                    |
       v                      v                    v
  [ Client listener ]   (só o leader processa pedidos e propõe)
  [ APL ]  <--------->  [ APL ]  <--------->  [ APL ]   (consenso entre members)
  [ Fair Loss ]         [ Fair Loss ]        [ Fair Loss ]
  [ UDP ]               [ UDP ]              [ UDP ]
       |                      |                    |
       v                      v                    v
  [ HotStuffReplica ]   [ HotStuffReplica ]  [ HotStuffReplica ]
       |                      |                    |
       v                      v                    v
  [ BlockchainService ]  (array de strings; atualizado no upcall DECIDE)
```

- **Cliente**: envia pedidos de append para **todos** os members (não sabe quem é o leader). Espera uma resposta (índice ou falha).
- **Members**: cada um tem uma **porta de consenso** (UDP + Fair Loss + APL) para falar com os outros, e uma **porta de clientes** para receber pedidos. Só o **leader da view atual** retira pedidos da fila e propõe blocos no HotStuff. Quando qualquer member **recebe a mensagem DECIDE**, faz o upcall (append ao array) e pode enviar resposta ao cliente.

---

## 4. Estrutura de pastas e ficheiros

```
depchain/
├── config/          # Configuração estática (endereços, chaves, membership)
├── transport/       # UDP e Fair Loss Link
├── links/           # Authenticated Perfect Links (APL)
├── consensus/       # HotStuff: blocos, fases, QC, mensagens, réplica
├── blockchain/      # Serviço (array de strings) e BlockchainMember
├── client/          # Protocolo cliente e DepChainClient
└── demo/            # Aplicação de demonstração (N members + 1 client)
```

Abaixo cada pacote e ficheiro é explicado por ordem lógica (de baixo para cima na pilha).

---

## 5. Configuração: `config/`

### 5.1 `NodeAddress.java`

- **O que é**: Representa o endereço de rede de um nó (host + port).
- **Para que serve**: Tanto para members (consenso e clientes) como para o cliente saber para onde enviar. Usa `InetSocketAddress` para enviar/receber UDP.
- **Raciocínio**: O enunciado diz que a membership (ids e endereços) é conhecida de antemão; por isso precisamos de um tipo imutável que identifique um nó na rede.

### 5.2 `Membership.java`

- **O que é**: A **membership estática**: lista de ids dos members, endereço de cada um e **chave pública** de cada um (PKI do enunciado).
- **Campos importantes**:
  - `n`: número total de nós.
  - `f`: máximo de Bizantinos tolerados, `f = (n-1)/3`.
  - `getQuorumSize()`: devolve `2f+1` (número de votos necessários para um QC).
  - `getLeaderId(viewNumber)`: leader da view é `memberIds.get(viewNumber % n)`.
- **Raciocínio**: O consenso e a APL precisam de saber quem são os nós, onde estão e qual a chave pública de cada um para verificar assinaturas. Tudo é fixo antes do arranque.

---

## 6. Transporte: `transport/`

O enunciado exige **UDP** como base. Em cima construímos um link “best-effort” (Fair Loss) e depois a APL.

### 6.1 `UdpTransport.java`

- **O que faz**:
  - Abre um `DatagramSocket` numa porta.
  - `send(payload, dest)`: envia os bytes para o `NodeAddress` de destino.
  - Uma thread em background faz `receive()` e coloca mensagens numa fila; `poll()` devolve a próxima mensagem (ou null).
- **O que não garante**: mensagens podem perder-se, chegar em duplicado, atrasadas ou corrompidas (UDP não faz retransmissão nem verificação).
- **Raciocínio**: É a camada mais baixa; não usamos TCP/TLS. Cada member tem uma instância a escutar na sua porta de consenso.

### 6.2 `FairLossLink.java`

- **O que faz**: Envolve o `UdpTransport`. No `send()`, envia uma vez imediatamente e agenda **retransmissões** (mais algumas vezes com atraso). Assim aproximamos o comportamento de “fair loss”: se a rede às vezes perde, reenviar aumenta a probabilidade de pelo menos uma cópia chegar.
- **O que não faz**: Não deduplica. Duplicados passam para cima; a **APL** é que vai garantir “no máximo uma entrega por mensagem” (e só após verificar assinatura).
- **Raciocínio**: A disciplina fala em construir abstrações sobre fair loss; aqui esta camada só tenta melhorar a entrega, sem alterar a semântica de “pode duplicar”.

---

## 7. Links autenticados: `links/`

Aqui garantimos **autenticidade** (quem enviou, integridade) e **entrega perfeita** (sem duplicados) para mensagens entre members. A ordem é **sempre**: primeiro verificar assinatura, depois deduplicar.

### 7.1 `APLMessage.java`

- **O que é**: Formato de **mensagem na rede** para a APL.
- **Conteúdo lógico**: `senderId`, `messageId`, `payload` (bytes).
- **Na prática**: O que é assinado é o “conteúdo a assinar”: `senderId + messageId + payload`. A mensagem na rede é: `[ tamanho do conteúdo | conteúdo | tamanho da assinatura | assinatura ]`.
- **Métodos**:
  - `getSignedContent()`: bytes que cada nó assina (para enviar) ou verifica (ao receber).
  - `encode(signedContent, signature)`: serialização para enviar.
  - `parse(wire)`: desserializa; devolve `Parsed` (senderId, messageId, payload, signedContent, signature) ou null se mal formado.
- **Raciocínio**: Precisamos de um formato fixo para que todos os nós interpretem igual; a assinatura cobre todo o conteúdo para evitar alteração ou reutilização por outro nó.

### 7.2 `AuthenticatedPerfectLink.java`

- **O que faz**:
  - **Enviar**: constrói uma `APLMessage` (senderId = selfId, messageId novo, payload), assina o conteúdo com a chave privada do nó, e envia via `FairLossLink` para o destino (usando `Membership.getAddress(destId)`).
  - **Receber** (`poll()`):
    1. Tira uma mensagem raw do Fair Loss.
    2. Faz **parse**; se falhar, descarta.
    3. Verifica se `senderId` está na membership e obtém a chave pública.
    4. **Verifica a assinatura** com essa chave; se falhar, descarta.
    5. **Só depois** vê se já viu este `(senderId, messageId)` (deduplicação); se já viu, descarta.
    6. Entrega a mensagem (devolve `DeliveredMessage(senderId, payload)`).
- **Porquê esta ordem**: Se deduplicássemos antes de verificar a assinatura, um atacante poderia enviar muitas mensagens com o mesmo messageId (falsificado); quando a mensagem verdadeira chegasse, seria descartada como “duplicado”. Por isso: **sempre primeiro autenticar, depois deduplicar**.
- **Raciocínio**: Isto implementa “Authenticated Perfect Links” como na disciplina: integridade + autenticação + entrega exatamente uma vez por (sender, messageId).

---

## 8. Consenso HotStuff: `consensus/`

Aqui está o núcleo do algoritmo: blocos, fases, certificados de quorum, formato de mensagens e a lógica da réplica.

### 8.1 `Block.java`

- **O que é**: Um bloco que será proposto e decidido numa view.
- **Campos**: `viewNumber`, `payload` (array de bytes). No Stage 1 o payload pode ser uma string ou, quando vem do cliente, `requestId` (8 bytes) + string.
- **getBlockHash()**: Deriva um identificador estável do bloco (view + tamanho + payload). Usado para votos e para o lock (só votamos em blocos com o mesmo hash que pre-committámos).
- **Raciocínio**: O paper fala em “block”; não precisamos de cadeia de hashes (sem chaining), só de um valor que todos acordam.

### 8.2 `Phase.java`

- Enum com as **4 fases**: `PREPARE`, `PRE_COMMIT`, `COMMIT`, `DECIDE`.
- Usado em mensagens (que fase é este QC) e na máquina de estados da réplica.

### 8.3 `QuorumCertificate.java`

- **O que é**: Um **QC** certifica que **2f+1** réplicas votaram num certo bloco numa certa fase.
- **Campos**: `viewNumber`, `phase`, `blockHash`, `signatures` (map replicaId → assinatura).
- No paper o QC pode ser um único “threshold signature”; no nosso projeto usamos um **map de assinaturas individuais** (cada réplica assina com a sua chave; o leader junta 2f+1). O enunciado permite esta alternativa.
- **Raciocínio**: Para segurança, temos de verificar que cada assinatura no QC é válida com a chave pública desse replica (feito em `HotStuffReplica.verifyQC`).

### 8.4 `ConsensusMessage.java`

- **O que é**: Formato de **todas** as mensagens do protocolo HotStuff (tipos 1–7).
- **Tipos**:
  - PREPARE (1): view, block, highQC.
  - PREPARE_VOTE (2): view, blockHash, assinatura.
  - PRE_COMMIT (3): view, block, prepareQC.
  - PRE_COMMIT_VOTE (4): view, blockHash, assinatura.
  - COMMIT (5): view, block, preCommitQC.
  - COMMIT_VOTE (6): view, blockHash, assinatura.
  - **DECIDE (7)**: view, **commitQC** (sem block; o block já está implícito no QC).
- **Métodos**: `encodePrepare`, `encodePrepareVote`, … `encodeDecide` e `parse(wire)` que devolve um `Message` com os campos conforme o tipo.
- **Raciocínio**: O consenso corre em cima da APL; o “payload” que a APL transporta são estes bytes. Ter um único módulo de serialização evita erros e mantém compatibilidade entre nós.

### 8.5 `ConsensusNetwork.java` (interface)

- **O que é**: Interface para “enviar e receber mensagens de consenso”.
- **Métodos**: `sendToAll(payload)`, `sendTo(memberId, payload)`, `poll()` → `ReceivedMessage(senderId, payload)`.
- **Raciocínio**: Assim o HotStuff não depende diretamente da APL. Nos testes podemos injetar uma implementação que **droppa** ou **corrompe** mensagens (testes intrusivos); em produção usamos a implementação real.

### 8.6 `APLConsensusNetwork.java`

- Implementação de `ConsensusNetwork` que usa `AuthenticatedPerfectLink` e `Membership`:
  - `sendToAll`: envia o mesmo payload para cada member (via APL).
  - `sendTo(id)`: envia só para esse member (usado para votos, que vão para o leader).
  - `poll()`: faz poll na APL e devolve `ReceivedMessage` com o senderId e o payload (já autenticado e deduplicado).

### 8.7 `HotStuffReplica.java` (núcleo do consenso)

Este é o ficheiro mais longo; aqui está a lógica do Basic HotStuff.

**Estado principal**:
- `view`: número da view atual.
- `phase`: PREPARE, PRE_COMMIT, COMMIT ou DECIDE.
- `currentBlock`: bloco em discussão nesta view.
- `lockedBlockHash`: após pre-commit, “travamos” neste bloco; só votamos em propostas compatíveis.
- `highQC`: último QC que temos (usado pelo leader no próximo PREPARE).
- `votes`: mapa de votos recebidos na fase atual (só o leader usa).
- `pendingProposals`: fila de blocos a propor (só o leader retira e propõe).
- `lastProgressTimeMs` e `viewTimeoutMs`: para o pacemaker (timeout de view).

**Fluxo (thread principal, `processLoop`)**:
1. Faz `poll()` na rede; se chegar uma mensagem, chama `handleMessage(senderId, payload)`.
2. Se for leader, tenta propor um bloco pendente (`tryProposePending`): só envia PREPARE se estiver em fase PREPARE e houver um bloco na fila.
3. Se passou tempo suficiente sem progresso (`lastProgressTimeMs`), chama `onViewTimeout()`: view++, volta a PREPARE, limpa votos e currentBlock (pacemaker).

**Tratamento de mensagens**:
- **PREPARE**: Só aceita se for do leader desta view. Verifica o lock (se temos lockedBlockHash, a proposta tem de ser compatível). Atualiza `currentBlock`, envia PREPARE_VOTE ao leader.
- **PRE_COMMIT**: Verifica prepareQC (fase PREPARE, 2f+1 assinaturas, blockHash igual ao block). Faz **lock** (`lockedBlockHash = block.getBlockHash()`), envia PRE_COMMIT_VOTE.
- **COMMIT**: Verifica preCommitQC; envia COMMIT_VOTE.
- **DECIDE**: Verifica commitQC. **Só aqui** chama `decideCallback.onDecide(block)` (upcall) e avança a view (view++, phase = PREPARE, currentBlock = null). Isto implementa a “4.ª fase como ronda real”: a decisão e o upcall acontecem quando se **recebe** a mensagem DECIDE.
- **Votos (PREPARE_VOTE, PRE_COMMIT_VOTE, COMMIT_VOTE)**: Só o leader processa. Verifica assinatura do voto, adiciona ao `votes`. Quando atinge 2f+1 votos, constrói o QC dessa fase, envia a mensagem da fase seguinte (PRE_COMMIT, COMMIT ou **DECIDE**). Quando envia DECIDE (após juntar 2f+1 COMMIT_VOTEs), o leader também faz localmente o upcall e avança a view (porque não “recebe” a sua própria mensagem DECIDE).

**Assinaturas**: Cada voto é assinado sobre `(view, phase, blockHash)`. O leader verifica cada voto com `membership.getPublicKey(senderId)` antes de o contar. Um QC válido tem pelo menos 2f+1 assinaturas válidas.

**Raciocínio**:
- Lock + regra de voto em PREPARE garantem que não decidimos um bloco que contradiz um pre-commit anterior (segurança).
- DECIDE como mensagem explícita garante que todas as réplicas executam o upcall no mesmo ponto do protocolo.
- O pacemaker garante que, se o leader falhar ou a rede atrasar, eventualmente mudamos de view e um novo leader continua com o highQC.

---

## 9. Blockchain: `blockchain/`

### 9.1 `BlockchainService.java`

- **O que é**: O “livro de registos” em memória: uma lista append-only de strings.
- **Métodos**:
  - `onDecide(Block block)`: chamado quando o consenso decide um bloco com payload “simples” (sem requestId). Adiciona `new String(block.getPayload(), UTF_8)` ao log.
  - `appendString(String s)`: adiciona diretamente (usado pelo `BlockchainMember` quando o payload é requestId + string).
  - `getLog()`, `size()`: leitura.
- **Raciocínio**: No Stage 1 não há persistência nem estrutura de blocos encadeados; é só um array para o upcall do consenso e para o cliente ver o resultado.

### 9.2 `BlockchainMember.java`

- **O que é**: Um **nó completo**: consenso (HotStuff) + receção de pedidos de clientes + blockchain (array) + envio de respostas ao cliente.
- **Construção**:
  - Cria `UdpTransport` (porta de consenso), `FairLossLink`, `AuthenticatedPerfectLink`, `APLConsensusNetwork`.
  - Cria `HotStuffReplica` com um callback `this::onDecide`.
  - Abre outro `DatagramSocket` na **porta de clientes** e inicia uma thread `clientListenLoop` que recebe datagramas, faz `ClientProtocol.parseRequest` e, se válido, guarda `(requestId → endereço do cliente)` e mete o pedido em `pendingRequests`.
  - Inicia uma thread `startLeaderProposeLoop` que, **só quando este nó é o leader** (`membership.getLeaderId(replica.getView()) == selfId`), retira um pedido de `pendingRequests`, monta um payload `requestId (8 bytes) + string`, e chama `replica.propose(new Block(...))`.
- **onDecide(Block block)**:
  - Se o payload tiver pelo menos 8 bytes, interpreta como `requestId` (8 bytes) + string. Chama `blockchain.appendString(string)`, procura o endereço do cliente em `requestIdToClient` e envia uma resposta (requestId, success, index) em UDP para esse endereço.
  - Caso contrário (payload “simples”), chama `blockchain.onDecide(block)`.
- **Raciocínio**:
  - O cliente faz broadcast; qualquer member pode receber o pedido. Todos guardam (requestId, endereço) para depois poderem responder. Só o leader propõe, para não haver propostas duplicadas.
  - O bloco que vai ao consenso contém requestId + string para que, ao decidir, saibamos a quem responder e qual o índice no log.

---

## 10. Cliente: `client/`

### 10.1 `ClientProtocol.java`

- **O que é**: Formato das mensagens **cliente ↔ member** (não usa APL; é UDP direto na porta de clientes).
- **Pedido**: tipo 0, requestId (8), tamanho da string (4), string em UTF-8. Tamanho máximo da string: `MAX_STRING_LENGTH` (evita abuso).
- **Resposta**: tipo 1, requestId, success (boolean), index (int).
- **parseRequest**: devolve null se os bytes não cumprirem o formato ou se o tamanho for inválido. Assim, mensagens mal formadas são **detetadas e ignoradas** (requisito do enunciado).
- **Raciocínio**: Protocolo mínimo e fácil de validar; o member nunca propõe com base em bytes inválidos.

### 10.2 `DepChainClient.java`

- **O que é**: A **client library** que a aplicação usa para fazer `append(string)`.
- **Funcionamento**:
  - Construtor: recebe lista de endereços dos members (portas de **clientes**) e uma porta onde o próprio cliente escuta para **respostas**. Abre um `DatagramSocket` nessa porta e inicia uma thread que faz `receive()` e, ao receber uma resposta válida (`ClientProtocol.parseResponse`), completa o `CompletableFuture` associado ao requestId.
  - `append(string)`: gera um requestId, regista um `CompletableFuture` para esse requestId, codifica o pedido com `ClientProtocol.encodeRequest`, e envia (UDP) para **todos** os member addresses. Faz até `maxRetries` tentativas; em cada uma espera `timeoutMs` pela resposta. Se o future for completado com a resposta correta, devolve o índice (ou -1 em falha).
- **Raciocínio**: O cliente não sabe quem é o leader; por isso envia a todos. Quem for leader processa; quando o bloco é decidido, um member (qualquer um que tenha o endereço do cliente) envia a resposta e o cliente desbloqueia.

---

## 11. Demo e testes

### 11.1 `demo/Demo.java`

- Cria N=4 members (membership com ids 0..3, endereços e chaves geradas), cada um com `BlockchainMember` (porta de consenso e porta de clientes).
- Cria um `DepChainClient` com a lista de endereços das **portas de clientes** dos 4 members e uma porta para escutar respostas.
- Chama `client.append("demo-string-1")`, etc., e imprime o resultado e o conteúdo do array num member.
- Serve para demonstrar o fluxo completo sem ficheiros de config.

### 11.2 Testes (resumo)

- **AuthenticatedPerfectLinkTest**: envio entre dois nós, rejeição de mensagem corrompida, deduplicação (envio repetido com o mesmo messageId só é entregue uma vez).
- **HotStuffIntegrationTest**: 4 réplicas decidem um bloco e vários blocos; teste de view change (leader não propõe, timeout, novo leader propõe).
- **ByzantineTest**: uma réplica usa `CorruptingConsensusNetwork` que corrompe os bytes do voto; as outras 3 têm votos válidos e o sistema ainda atinge quorum e decide.
- **IntrusiveNetworkTest**: consenso com uma rede que pode dropar mensagens (DropConsensusNetwork), para validar resiliência.
- **ClientIntegrationTest**: 4 BlockchainMembers + 1 DepChainClient; client faz append e verifica que o array num member contém a string.

---

## 12. Fluxo completo de um `append(string)` (para fixar ideias)

1. Aplicação chama `DepChainClient.append("hello")`.
2. Cliente gera requestId, codifica pedido (tipo 0, requestId, "hello"), envia por UDP para a **porta de clientes** de cada member.
3. Cada member recebe na sua porta de clientes; faz `ClientProtocol.parseRequest`. Se válido, guarda (requestId → endereço do cliente) e coloca o pedido em `pendingRequests`.
4. O **leader** da view atual (por exemplo member 0) na thread `startLeaderProposeLoop` retira o pedido, monta payload = requestId + "hello", e chama `replica.propose(new Block(view, payload))`.
5. HotStuff: leader envia PREPARE(view, block, highQC) a todos; réplicas votam; leader junta 2f+1 PREPARE_VOTEs em prepareQC, envia PRE_COMMIT; depois COMMIT; depois, com commitQC, envia **DECIDE**.
6. Cada member (incluindo o leader) ao processar a mensagem DECIDE chama `onDecide(block)`. No `BlockchainMember`, como o payload tem 8+ bytes, faz append da string ao `BlockchainService`, procura o endereço do cliente para este requestId e envia a resposta (tipo 1, requestId, success, index) por UDP para esse endereço.
7. O cliente recebe a resposta na sua thread de receive, completa o `CompletableFuture`, e `append()` devolve o índice.

Se algo correr mal (rede, leader em falha), o pacemaker faz mudar de view; o novo leader pode propor (incluindo o mesmo pedido se ainda estiver na fila) e o processo repete até decidir.

---

## 13. O que NÃO está no projeto (e está correto)

- **Chained HotStuff** (Section 5 do paper): não implementado; o enunciado pede só Basic HotStuff.
- **TLS**: não usado; a autenticação é feita com assinaturas em cima de UDP.
- **Threshold signatures** (biblioteca externa): não usada; usamos mapa de assinaturas individuais, que o enunciado permite como alternativa.
- **Persistência** do array: não há; é em memória, como indicado para o Stage 1.

Com este guia consegues percorrer todo o código com o enunciado e o paper em mente. Se quiseres, podemos aprofundar uma parte específica (por exemplo só o HotStuffReplica ou só a APL) noutro documento ou secção.
