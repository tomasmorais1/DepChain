# Como correr o DepChain manualmente

## Pré-requisitos

- **Java 11+**
- **Maven 3.6+**

Na raiz do projeto:

```bash
mvn clean compile
```

---

## Opção 1: Um único terminal (demo atual)

A demo arranca **4 members** e **1 client** no **mesmo processo** (mesma JVM). Basta um terminal.

### Com Maven

```bash
mvn exec:java -Dexec.mainClass="depchain.demo.Demo"
```
ou

mvn compile exec:java

### Com JAR (depois de fazer package)

```bash
mvn package
java -jar target/depchain-1.0-SNAPSHOT.jar
```

### Com java -cp (sem criar JAR)

```bash
mvn compile
java -cp "target/classes" depchain.demo.Demo
```

Saída esperada: o cliente faz append de 3 strings e no fim imprime o conteúdo do array num member (e.g. `Blockchain content at member 0: [demo-string-1, demo-string-2, demo-string-3]`).

---

## Opção 2: Cinco terminais (5 JVMs — um processo por member e um pelo client)

Cada **member** e o **client** correm em **processos separados** (5 JVMs). É **obrigatório** gerar um ficheiro de chaves **uma vez** (`genconfig`); os 4 members leem as chaves desse ficheiro para coincidirem.

### 1. Compilar

Na raiz do projeto:

```bash
mvn compile
```

### 2. Gerar o ficheiro de chaves (uma vez)

**Só precisas de fazer isto uma vez** (ou quando apagares o ficheiro). Na raiz do projeto:

```bash
java -cp "target/classes" depchain.Main genconfig
```

Isto cria o ficheiro `depchain-multijvm.keys` na pasta atual. Todos os members vão usar estas chaves.

### 3. Arrancar os 4 members (4 terminais)

Abre **4 terminais**. Em cada um, na raiz do projeto (onde correste `genconfig`):

**Terminal 1:**
```bash
java -cp "target/classes" depchain.Main member 0
```

**Terminal 2:**
```bash
java -cp "target/classes" depchain.Main member 1
```

**Terminal 3:**
```bash
java -cp "target/classes" depchain.Main member 2
```

**Terminal 4:**
```bash
java -cp "target/classes" depchain.Main member 3
```

Cada um imprime algo como `Member X running (consensus port 3000X, client port 3010X). Press Enter to stop.` e fica à espera.

**Importante:** Confirma que **os 4** terminais mostram essa mensagem (Member 0, 1, 2 e 3). Se algum falhar ao arrancar (ex.: "Address already in use"), esse member não está a correr. Depois de os 4 estarem a correr, **espera 4–5 segundos** antes de arrancar o client.

### 4. Arrancar o client (5.º terminal)

Noutro terminal, na raiz do projeto (o client espera 4 s automaticamente antes de enviar):

```bash
java -cp "target/classes" depchain.Main client
```

O client envia 3 strings (`multi-jvm-1`, `multi-jvm-2`, `multi-jvm-3`) e imprime o índice de cada append (0, 1, 2 em caso de sucesso). No fim termina.

**Se aparecer `index -1`:** (1) Correste `genconfig` antes de arrancar os members? (2) **Tens os 4 members a correr** (member 0, 1, 2, 3)? O consenso precisa de 3 de 4 (quorum); se só um member estiver ativo, nunca há DECIDE. (3) As portas 30000–30003 e 30100–30103 estão livres?

**Logs de diagnóstico (stderr):** No **client**: `[client] send requestId=...`, `[client] recv response` (se receber), `[client] timeout` (se não receber). Em **cada member**: `[member N] received client request` (pedido do client chegou), `[member N] leader sent PREPARE view 0` (só o leader, member 0), `[member N] consensus recv PREPARE from 0` (outros members a receber do leader), `[member N] onDecide ... sent response` (quando o bloco é decidido e a resposta é enviada ao client). Se só vês "received client request" no member 0 e nenhum "consensus recv" em nenhum member, o mais provável é **não teres os 4 members a correr** ou as portas de consenso (30000–30003) estarem em uso/erradas.

### 5. Parar os members

Em cada um dos 4 terminais dos members, carrega **Enter** para fechar esse member.

### Portas (iguais à demo)

| Nó        | Porta consenso | Porta clientes |
|-----------|-----------------|----------------|
| Member 0  | 30000           | 30100          |
| Member 1  | 30001           | 30101          |
| Member 2  | 30002           | 30102          |
| Member 3  | 30003           | 30103          |
| Client    | —               | escuta em **30200** (respostas) |

O client envia pedidos para as portas **30100–30103**.

---

*Ver também: [README.md](../README.md) para build, testes e estrutura do projeto.*
