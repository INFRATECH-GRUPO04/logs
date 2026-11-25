# Plano de Implementação — Alertas lendo os CSVs da Silver (InfraTech / repo `logs`)

> **Este documento é autossuficiente.** Foi escrito para uma sessão nova, sem histórico de conversa
> e possivelmente com outro modelo. Leia **todo** antes de mexer no código e execute na ordem.
> Todo o trabalho acontece em `/home/luiz/Documentos/repositories/logs` (aplicação Java).
> Os outros repositórios citados são **somente referência de leitura** — **não os altere nesta tarefa**.
> O acordo completo também está registrado no `AGENTS.md` dos 4 repositórios, seção 9.

---

## 1. Escopo e premissas

**Objetivo desta tarefa:** substituir a leitura de `latest.json` pela leitura **direta dos CSVs da
camada silver** (`02-silver/metricas/`), mantendo o restante do fluxo de alertas (MySQL → Jira →
Slack). Nenhuma Lambda, nenhum script Python e nenhum arquivo do site são modificados aqui.

**Decisão central (não rediscutir):** **não existe mais `latest.json` em nenhum prefixo.** O Java é
que faz a conversão dos CSVs da silver para as amostras que a lógica de alerta consome. Isso evita
ter que mexer na Lambda bronze→silver.

**Estado dos outros repositórios (somente leitura):**

| Repo | Papel | O que é esperado dele |
|---|---|---|
| `script_python` | agente local | enviar lotes com `POST /agente/enviar`; o usuário vai ajustar para `INTERVALO = 60`, `CAPTURAS_POR_LOTE = 10` **depois** — não fazer isso aqui |
| `InfraTech-SiteInstitucional` | back-end + dashboards | grava `01-bronze/`; lê `03-gold/empresa-{id}/dash-geral.json`; nada muda |
| `etl` | duas Lambdas | bronze→silver fica como está; silver→gold passa a rodar a cada 30 min com janela de 40 dias e gravar `dash-geral.json` — mudança feita pelo usuário em outra tarefa |
| `logs` | Java desta tarefa | ler `02-silver/metricas/`, comparar com limites no MySQL, criar chamados no Jira e mensagens no Slack |

**Regras do `AGENTS.md` que valem aqui:**

- Jira/Slack são ferramentas **internas da InfraTech**; todos os chamados vão para o mesmo projeto.
- Dados isolados por empresa; IDs vêm dos próprios dados, nunca de suposição.
- Não há testes, lint nem CI: validação é manual.
- Código simples, comentários em português, sem refatoração ampla, sem hardening de segurança extra.
- Em caso de conflito, **o código atual vale mais que este documento** — sinalize e pergunte.

---

## 2. Estado atual do repo `logs` (já implementado — não refazer)

Estas mudanças já estão no working tree (ainda sem commit):

| Arquivo | Situação |
|---|---|
| `pom.xml` | shade plugin + `Main-Class: school.sptech.IntegracaoJiraSlack`; **não mexer** (parser será manual) |
| `src/main/java/school/sptech/DatabaseConfig.java` | lê `DB_HOST`, `DB_DATABASE`, `DB_USER`, `DB_PASSWORD`; porta fixa `3306` |
| `src/main/java/school/sptech/S3Provider.java` | `S3Client` com `Region.US_EAST_1` fixo e `DefaultCredentialsProvider`; **não parametrizar região** |
| `src/main/java/school/sptech/S3Service.java` | `listarArquivos()` **sem paginação** e `lerArquivoGold()`; precisa ganhar paginação |
| `src/main/java/school/sptech/IntegracaoJiraSlack.java` | loop + watermark por `gerado_em`, lê `prefixo + "latest.json"`, `extrairServidores()`, `avaliar()` com janela de 5 min e 2 amostras consecutivas, rede agregada, labels no Jira, descrição |
| `src/main/java/school/sptech/config/Jira.java` | `createIssue(..., labels)` em `/rest/api/2/issue`; `existeChamadoAberto()` em `/rest/api/3/search/jql` com **fail-open** |
| `src/main/java/school/sptech/config/Slack.java` | webhook vazio é tolerado (avisa e pula) |
| `Dockerfile`, `.dockerignore`, `.env.example`, `README.md` | já existem; precisam só da atualização das variáveis descrita em §6 |

`mvn -q package` já passa e gera jar gordo (~23 MB). Não recriar nada disso.
`docs/` é diretório novo (não versionado); este arquivo é o plano vigente.

---

## 3. Contrato de dados — CSV da Silver (fonte da verdade)

### 3.1 Chaves no S3

Prefixo: `02-silver/metricas/` (configurável por `S3_SILVER_PREFIX`).

```
02-silver/metricas/empresa_id=2/servidor_id=3/data=2026-11-24/20261124T224100Z_empresa-2_servidor-3_dados.csv
```

- `empresa_id` e `servidor_id`: inteiros, os mesmos IDs do MySQL.
- `data=AAAA-MM-DD`: partição por dia, **no meio da chave** (não dá para filtrar com um único prefixo por data).
- `<lote>`: `YYYYMMDDTHHMMSSZ`. O `Z` é enganoso: o valor é gerado com
  `datetime.now(FUSO_BRASILIA)` em `script_python/captura_servidor.py`. **Interprete como
  `America/Sao_Paulo`, não como UTC.**
- Um lote que cruza meia-noite gera **dois** arquivos, com `data=` diferente e o **mesmo `lote`**.
- Arquivos `_processos.csv` existem no bronze, mas a silver só recebe `_dados.csv`; ignore qualquer
  chave que não termine em `_dados.csv`.

### 3.2 Cabeçalho exato (`dados`)

Definido em `CABECALHOS.dados` no `agenteController.js` do site; o Java é o 3º consumidor dele:

```
id_empresa,id_servidor,username,data_hora,cpu,inte_cpu,ram_disponivel,ram_total,ram,disco,disco_disponivel,disco_total,bytes_enviados,bytes_recebidos,pacotes_enviados,pacotes_recebidos,trocas_contexto,temperatura_cpu,tempo_escrita_disco
```

Campos usados pelo Java:

| CSV | Uso |
|---|---|
| `id_empresa`, `id_servidor` | identificação; validar `> 0` |
| `username` | nome do servidor (fallback `Servidor {id}`) |
| `data_hora` | `YYYY-MM-DD HH:MM:SS`, hora de Brasília **sem offset** |
| `cpu`, `ram`, `disco` | percentuais (0–100) |
| `bytes_enviados`, `bytes_recebidos` | **contadores cumulativos**, não Mbps |

Campos ausentes, vazios ou não numéricos viram `null` → **sem dados**, nunca zero.

### 3.3 Conversão da rede

O agente faz `bytes_enviados = bytes_enviados + ...` a cada captura. Então, para duas linhas
consecutivas `a` e `b` ordenadas por `data_hora`:

```text
segundos = (b.data_hora - a.data_hora) em segundos   // >= 1
mbps_up   = (b.bytes_enviados   - a.bytes_enviados)   / segundos * 8 / 1_000_000
mbps_down = (b.bytes_recebidos - a.bytes_recebidos)   / segundos * 8 / 1_000_000
```

- Delta negativo (reset do contador) → `0`.
- `Δt` **sempre calculado dos timestamps**, nunca fixo em 60 s.
- A primeira linha do arquivo não tem delta próprio: usar a última linha do **lote anterior** do mesmo
  servidor. Se não houver lote anterior, essa linha fica sem rede (o Java já ignora
  `uploadMbps == null` na agregação).
- O limite de rede do banco pode estar em `%` ou `Mbps`; a lógica atual de conversão usando
  `componente.capacidade_total` continua válida.

### 3.4 Formatos de data no Java

O helper `timestamp()` existente em `IntegracaoJiraSlack.java` já faz o certo:

- string ISO com `Z`/offset → `Instant.parse(dataHora)`;
- string `YYYY-MM-DD HH:MM:SS` → `LocalDateTime.parse(...replace(' ', 'T'))` com
  `ZoneId.of("America/Sao_Paulo")`.

**Não trocar por UTC e não usar o relógio da máquina como referência da janela.**

---

## 4. Decisões consolidadas (não rediscutir)

| Tema | Decisão |
|---|---|
| Fonte dos dados | CSVs da `02-silver/metricas/`, lidos pelo Java; **sem `latest.json`** |
| Gatilho | loop do Java; a cada rodada faz `LIST` paginado; só faz `GET` quando há lote novo por servidor |
| Watermark | **por `(empresa, servidor)`**, usando o `lote` do nome do arquivo; mapa em memória |
| Lote anterior | baixado junto para calcular o 1º delta de rede |
| Referência da janela | maior `data_hora` lido (equivalente ao antigo `gerado_em`) |
| Janela | **10 minutos**, configurável |
| Severidade | **média** das amostras selecionadas define `CRÍTICO`/`ATENÇÃO` |
| Confirmação | **7 de 10** amostras acima do limite escolhido, **não precisam ser consecutivas** |
| Amostras selecionadas | as **10 mais recentes** dentro da janela; até o usuário ajustar o agente para 1 min, o lote pode ter mais linhas — pegue as 10 últimas e **não** crie agregação por minuto |
| Sem dados novos | se a referência ficar com mais de **15 min**, avisar no log uma vez e **não** criar chamado; checar **antes** do `return` do watermark |
| Parser | `split(",", -1)` com mapa índice←nome do cabeçalho; **sem dependência Maven nova** |
| Listagem | paginada (`listObjectsV2Paginator`) |
| Variáveis novas | `S3_SILVER_PREFIX`, `ALERTA_JANELA_MINUTOS`, `ALERTA_MIN_AMOSTRAS`, `ALERTA_MAX_IDADE_MINUTOS`; `S3_ALERTA_PREFIX` sai |
| IAM | `ListBucket` em `02-silver/metricas*` além de `GetObject` |
| Jira | labels `empresa-{id}`, `servidor-{id}` (rede sem esta), `componente-{tipo}`, `severidade-{...}`; dedup por JQL fail-open |
| Slack | webhook vazio tolerado; chamado no Jira continua sendo criado |
| Descrição do chamado | inclui leitura mais recente e janela avaliada |
| Outros repos | **intactos** nesta tarefa |

---

## 5. Implementação — arquivos e ordem

### Tarefa 1 — `S3Service.java`: listagem paginada

O método atual (`:29-40`) chama `listObjectsV2` uma única vez. Trocar por paginação:

```java
    public List<String> listarArquivos(String bucketName, String prefixo) {
        List<String> chaves = new ArrayList<>();
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefixo)
                .build();
        for (ListObjectsV2Response pagina : s3Client.listObjectsV2Paginator(request)) {
            for (S3Object objeto : pagina.contents()) {
                chaves.add(objeto.key());
            }
        }
        return chaves;
    }
```

Manter `lerArquivoGold(bucket, key)` (pode ser renomeado para `lerArquivo`; verifique os chamadores —
hoje só `IntegracaoJiraSlack` usa). O corpo de leitura via `BufferedReader` está correto.

### Tarefa 2 — novo `src/main/java/school/sptech/SilverLeituraService.java`

Classe nova, responsável por descobrir e converter os CSVs. Responsabilidades:

1. **Descobrir chaves.** Usar `S3Service.listarArquivos(bucket, prefixo)`. Para cada chave:
   - ignorar se não terminar em `_dados.csv`;
   - remover o prefixo e casar com:
     ```java
     Pattern CHAVE = Pattern.compile(
         "^empresa_id=(\\d+)/servidor_id=(\\d+)/data=(\\d{4}-\\d{2}-\\d{2})/([^/]+)$");
     Pattern NOME = Pattern.compile(
         "^(\\d{8}T\\d{6}Z)_empresa-\\d+_servidor-\\d+_dados\\.csv$");
     ```
   - interpretar o `lote` como Brasília:
     ```java
     private static Instant instanteLote(String lote) {
         String semZ = lote.substring(0, lote.length() - 1);
         LocalDateTime local = LocalDateTime.parse(semZ,
                 DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
         return local.atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
     }
     ```
2. **Agrupar por servidor** (`empresa-servidor`) e por `lote`. O mesmo lote pode aparecer em duas
   chaves (meia-noite) — juntar as duas chaves antes de baixar.
3. **Selecionar o lote mais recente de cada servidor.** Só baixar se `lote` for diferente do
   watermark daquele servidor (`Map<String,String> ultimoLotePorServidor`, passada como parâmetro ou
   mantida no serviço). Baixar também o lote imediatamente anterior (para o 1º delta de rede).
4. **Parsear CSV** com `split(",", -1)`:
   ```java
   String[] cabecalho = primeiraLinha.split(",", -1);
   Map<String, Integer> colunas = new HashMap<>();
   for (int i = 0; i < cabecalho.length; i++) colunas.put(cabecalho[i].trim(), i);

   private static Double numero(String[] campos, Map<String,Integer> colunas, String nome) {
       Integer i = colunas.get(nome);
       if (i == null || i >= campos.length) return null;
       String v = campos[i].trim();
       if (v.isEmpty()) return null;
       try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
   }
   ```
5. **Montar as amostras** ordenadas por `data_hora` (usar o `timestamp()` existente, do Java).
   Calcular `uploadMbps`/`downloadMbps` por delta entre linhas consecutivas, incluindo a ponte
   entre o último registro do lote anterior e o primeiro do lote atual.
6. **Devolver** uma estrutura simples com:
   - `List<LeituraServidor>` com `idEmpresa`, `idServidor`, `nome`, `amostras` (a classe já existe em
     `IntegracaoJiraSlack` — reutilizar ou mover para um DTO, sem mudar os campos);
   - `Instant referencia` = maior `data_hora` entre todas as amostras carregadas;
   - `Map<String,String> lotesProcessados` = lote novo de cada servidor (para atualizar o watermark).

**Importante:** não fazer `GET` de todos os arquivos. `LIST` todo mundo, `GET` só dos lotes novos +
anteriores. Com o agente em 1 min e 10 capturas/lote, são no máximo 2 arquivos por servidor a cada
10 min.

### Tarefa 3 — `IntegracaoJiraSlack.java`: trocar a entrada e as regras

**3a. Variáveis no `main`:**

```java
String prefixo = System.getenv().getOrDefault("S3_SILVER_PREFIX", "02-silver/metricas/");
if (!prefixo.endsWith("/")) prefixo += "/";
```

**Remover** `S3_ALERTA_PREFIX`, `lerGeradoEm()` e `extrairServidores()` (ou deixar `lerGeradoEm`
fora de uso — mas não referenciar `latest.json` em log nenhum). Atualizar a mensagem de log de
`Lendo 03-gold/latest.json...` para algo como `Lendo CSVs de <prefixo>...`.

**3b. Estado em memória (trocar `ultimoGeradoEm`):**

```java
    private static final Map<String, String> ultimoLotePorServidor = new HashMap<>();
    private static Instant ultimaReferencia = null;
    private static boolean avisoStaleAtivo = false;
```

**3c. Reorganizar `processarRodada`:**

```java
    private static void processarRodada(...) throws Exception {
        if (apiToken == null || apiToken.isBlank()) {
            System.err.println("JIRA_TOKEN não configurado; rodada abortada.");
            return;
        }

        LeituraSilver leitura = silverService.ler(bucketName, prefixo, ultimoLotePorServidor);

        // checagem de dado velho ANTES do watermark:
        Instant referencia = leitura.referencia != null ? leitura.referencia : ultimaReferencia;
        long maxIdadeMin = configLong("ALERTA_MAX_IDADE_MINUTOS", 15);
        if (referencia == null
                || Duration.between(referencia, Instant.now()).toMinutes() > maxIdadeMin) {
            if (!avisoStaleAtivo) {
                System.err.println("Aviso: sem dados novos há mais de " + maxIdadeMin
                        + " min; nenhum chamado será criado.");
                avisoStaleAtivo = true;
            }
            return;
        }
        avisoStaleAtivo = false;
        ultimaReferencia = referencia;

        if (leitura.servidores.isEmpty()) {
            System.out.println("Nenhum lote novo; pulando rodada.");
            return;
        }

        // ... verificarComponente / rede / disparoAlerta, iguais aos atuais,
        //     mas passando 'referencia' como 'agora' ...

        ultimoLotePorServidor.putAll(leitura.lotesProcessados);
    }
```

O loop, `intervaloMs()`, criação única de `Jira`/`Slack`/`JdbcTemplate`/`S3Service` e o tratamento
de exceção permanecem como estão. Adicionar helper para configs numéricas (evitar `NumberFormatException`):

```java
    private static long configLong(String nome, long padrao) {
        try { return Long.parseLong(System.getenv().getOrDefault(nome, String.valueOf(padrao))); }
        catch (Exception e) { return padrao; }
    }
```

**3d. Nova regra em `avaliar(pontos, limites, referencia)`:**

```java
    private static final long JANELA_MS = configLong("ALERTA_JANELA_MINUTOS", 10) * 60_000L;
    private static final long MIN_AMOSTRAS = configLong("ALERTA_MIN_AMOSTRAS", 7);
```

- filtrar pontos com `referencia - JANELA_MS <= instante <= referencia`;
- ordenar e **limitar às 10 amostras mais recentes** (`AMOSTRAS_CONSIDERADAS = 10`, fixo);
- se vazio → `SEM_DADOS`;
- média dessas amostras → `CRITICO` / `ATENCAO` / `NORMAL`;
- `alvo` = crítico se `CRITICO`, senão atenção;
- contar `acima` = quantidade de pontos com `valor >= alvo`;
- `confirmado = acima >= MIN_AMOSTRAS`;
- **remover** a lógica antiga que zerava o contador quando aparecia uma amostra abaixo do limite
  (`amostras = 0`) e a exigência de distância mínima de 60 s entre a 1ª e a última. A janela de 10 min
  já delimita o período.

Campos do `Resultado` (`estado`, `media`, `amostras`, `confirmado`) permanecem; `amostras` agora é a
contagem de pontos acima do limite. Ajustar o texto da descrição (`Confirmado por N amostras em até
5 minutos.` → `... em até 10 minutos.`).

**3e. Descrição do Jira** (`disparoAlerta`): acrescentar linhas como:

```text
Leitura mais recente: 2026-11-24 22:41:04
Janela avaliada: 10 min (7 de 10 amostras acima do limite)
```

Formato do `data_hora` pode ser o string original; já é legível.

**3f. Rede:** manter a agregação por instante/empresa
(`uploadMbps + downloadMbps`, conversão para % com `capacidade_total`). Os pontos agora podem vir
com mais de um arquivo por servidor; ordenar antes de aplicar `avaliar`.

### Tarefa 4 — Variáveis, `.env.example` e `README.md`

`.env.example` — substituir o bloco do S3 e acrescentar as configs:

```
# Bucket e prefixo da camada silver (o Java lê os CSVs daqui)
S3_BUCKET=
S3_SILVER_PREFIX=

# Projeto Jira (igual ao JIRA_PROJECT do site)
JIRA_PROJECT=
JIRA_BASE_URL=
JIRA_EMAIL=
JIRA_TOKEN=
SLACK_WEBHOOK=

# MySQL na mesma network Docker
DB_HOST=
DB_DATABASE=
DB_USER=
DB_PASSWORD=

# Loop e regra de alerta
ALERTA_INTERVALO_SEGUNDOS=
ALERTA_JANELA_MINUTOS=
ALERTA_MIN_AMOSTRAS=
ALERTA_MAX_IDADE_MINUTOS=
```

Valores padrão no código (todos os demais continuam como hoje):

| Variável | Default | Observação |
|---|---|---|
| `S3_BUCKET` | `teste-integracao-java` | usar `s3-infratech-prod` no teste local |
| `S3_SILVER_PREFIX` | `02-silver/metricas/` | `S3_ALERTA_PREFIX` **removido** |
| `ALERTA_JANELA_MINUTOS` | `10` | nova |
| `ALERTA_MIN_AMOSTRAS` | `7` | nova |
| `ALERTA_MAX_IDADE_MINUTOS` | `15` | nova |
| `ALERTA_INTERVALO_SEGUNDOS` | `300` | já existe; manter |
| `JIRA_PROJECT` | `IFT` | site usa `IFT` |
| `JIRA_TOKEN`, `SLACK_WEBHOOK`, `DB_PASSWORD` | `""` | segredos vão no env file |

`README.md`: remover o contrato do `latest.json` e a menção a `S3_ALERTA_PREFIX`; documentar o
prefixo da silver, as 4 variáveis novas e que a role precisa de `ListBucket`. O restante do README
(build, Docker, DockerHub, EC2) permanece.

`pom.xml`, `Dockerfile`, `.dockerignore`: **sem mudança**.

---

## 6. IAM e infraestrutura

- Região `US_EAST_1` fixa em `S3Provider` — **não parametrizar**.
- Credenciais AWS por `DefaultCredentialsProvider` (IAM Role na EC2; localmente, `~/.aws` montado
  no container). Nada de chave no código.
- A política da role precisa de:
  - `s3:ListBucket` no bucket, com prefixo `02-silver/metricas*`;
  - `s3:GetObject` em `02-silver/metricas/*`.
- MySQL na mesma network Docker, porta fixa `3306`.
- IMDSv2 em container exige `HttpPutResponseHopLimit >= 2` na EC2.
- Egress: liberar HTTPS/443 para S3, Jira e Slack. MySQL não passa pelo SG.
- `--restart` não é usado (decisão do usuário).
- Webhook do Slack já está em `~/.config/infratech/alertas.env` e funcionou no último teste.

---

## 7. Validação manual (obrigatória)

### 7.1 Build

```bash
cd /home/luiz/Documentos/repositories/logs
mvn -q package
unzip -p target/java-jira-integration-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Main-Class
ls -lh target/java-jira-integration-1.0-SNAPSHOT.jar   # ~23 MB, não 30 K
docker build -t infratech-alertas:1.0 .
```

### 7.2 Dados de teste no S3

Use o bucket real `s3-infratech-prod` (já existe, em `us-east-1`). Inspecione o que a silver tem:

```bash
aws s3 ls s3://s3-infratech-prod/02-silver/metricas/ --recursive | tail -20
```

Para um teste determinístico, crie um CSV de 10 linhas para o servidor 3 da empresa 2 (limites de
CPU 75/90 no seed) e suba exatamente na chave da silver:

```python
import csv, io, os
from datetime import datetime, timedelta

cabecalho = ['id_empresa','id_servidor','username','data_hora','cpu','inte_cpu',
             'ram_disponivel','ram_total','ram','disco','disco_disponivel','disco_total',
             'bytes_enviados','bytes_recebidos','pacotes_enviados','pacotes_recebidos',
             'trocas_contexto','temperatura_cpu','tempo_escrita_disco']
agora = datetime.now() - timedelta(seconds=20)   # horário local de Brasília
up = down = 1_000_000
linhas = []
for i in range(10):
    t = agora - timedelta(minutes=9 - i)
    up += 37_500_000      # 5 Mbps
    down += 37_500_000    # 5 Mbps
    linhas.append(dict(zip(cabecalho, [
        2, 3, 'SRV-GAMECLOUD-01', t.strftime('%Y-%m-%d %H:%M:%S'),
        95, 0, 50, 100, 60, 50, 500, 1000, up, down, 0, 0, 0, 60, 0])))
buf = io.StringIO()
w = csv.DictWriter(buf, fieldnames=cabecalho, lineterminator='\n')
w.writeheader(); w.writerows(linhas)
lote = agora.strftime('%Y%m%dT%H%M%SZ')
dia = agora.strftime('%Y-%m-%d')
chave = f'02-silver/metricas/empresa_id=2/servidor_id=3/data={dia}/{lote}_empresa-2_servidor-3_dados.csv'
with open('/tmp/opencode/silver-teste.csv', 'w') as f: f.write(buf.getvalue())
with open('/tmp/opencode/chave-teste.txt', 'w') as f: f.write(chave)
print(chave)
```

```bash
CHAVE=$(cat /tmp/opencode/chave-teste.txt)
aws s3 cp /tmp/opencode/silver-teste.csv "s3://s3-infratech-prod/$CHAVE"
```

> Não invente o nome da chave: o Java casa o nome do arquivo com um regex e interpreta o `lote`
> como horário de Brasília. Use exatamente a chave impressa pelo Python.

### 7.3 Execução local

O container lê o env direto do arquivo (funciona em `fish`, `bash` ou qualquer shell):

```bash
cd /home/luiz/Documentos/repositories/logs
docker run --rm --name infratech-alertas --network host \
  -v ~/.aws:/root/.aws:ro \
  --env-file ~/.config/infratech/alertas.env \
  -e S3_SILVER_PREFIX=02-silver/metricas/ \
  -e ALERTA_JANELA_MINUTOS=10 \
  -e ALERTA_MIN_AMOSTRAS=7 \
  -e ALERTA_MAX_IDADE_MINUTOS=15 \
  -e ALERTA_INTERVALO_SEGUNDOS=10 \
  infratech-alertas:1.0
```

Se o arquivo de env ainda tiver `S3_ALERTA_PREFIX`, remover a linha ou ignorar — o código não lê
mais essa variável.

Acompanhar:

```bash
docker logs -f infratech-alertas
```

### 7.4 Checklist de aceitação

- [ ] `mvn package` sem erros; jar com `Main-Class` e ~23 MB.
- [ ] `docker build` termina; `docker run --env-file` sobe sem `NoSuchKey`.
- [ ] Log mostra o prefixo `02-silver/metricas/` e o lote encontrado.
- [ ] Servidor com 10 leituras de CPU 95 gera **um** chamado `CRÍTICO` com labels
      `empresa-2`, `servidor-3`, `componente-cpu`, `severidade-critico`.
- [ ] Descrição traz `Leitura mais recente` e `Janela avaliada` com 7 de 10.
- [ ] Rede aparece em Mbps no log/descrição (≈10 Mbps no CSV de teste) e o chamado de rede
      **não** tem label `servidor-{id}`.
- [ ] Rodada repetida sem lote novo **não** gera segundo chamado (watermark por servidor).
- [ ] Lote novo para um servidor não é ignorado por causa de lote mais novo de outro servidor.
- [ ] CSV com 7 de 10 acima do limite **nas duas colunas** gera chamado mesmo com uma amostra normal
      intercalada (não consecutivas).
- [ ] CSV com só 2 amostras acima do limite **não** gera chamado.
- [ ] Apagar/parar o agente e esperar >15 min: aviso no log, nenhum chamado novo.
- [ ] Sem `JIRA_TOKEN`: avisa e aborta a rodada.
- [ ] Sem `SLACK_WEBHOOK`: chamado criado no Jira, aviso no log.
- [ ] Falha na busca JQL: chamado criado (fail-open).
- [ ] JQL `project = "IFT" AND labels = "empresa-2" AND statusCategory != Done` retorna os chamados.
- [ ] Dashboard do site segue lendo `dash-geral.json` (sem alteração aqui).

---

## 8. Armadilhas conhecidas

1. **`Z` no lote não é UTC.** É Brasília. Parsear como UTC desloca 3 h e quebra o watermark.
2. **`data_hora` sem offset.** Usar `America/Sao_Paulo`, nunca `ZoneId.systemDefault()` nem UTC.
3. **Relógio da máquina.** A janela é contada a partir do maior `data_hora`, mas o aviso de 15 min
   compara com o relógio — mantenha NTP/chrony ativo.
4. **Meia-noite.** Um mesmo lote pode virar duas chaves; junte antes de marcar como processado.
5. **Contadores de rede.** São cumulativos; delta negativo → 0; `Δt` real dos timestamps.
6. **Ordenação.** Ordene linhas por `data_hora` antes de calcular deltas e antes de `avaliar`.
7. **Paginação.** Sem `listObjectsV2Paginator`, a lista fica incompleta quando há muitos arquivos.
8. **Agente ainda em 5s/5.** Até o usuário mudar para 60s/10, um lote tem poucas linhas e a janela
   de 10 min pode conter mais de 10 — pegue as 10 mais recentes. Não implemente buckets por minuto:
   decisão foi não fazer isso.
9. **Chamados antigos sem labels** não aparecem na dashboard — aceito.
10. **Token antigo no Git** não é rotacionado sem pedido.
11. **Docs antigos:** o README e versões anteriores deste arquivo falam em `latest.json` e
    `S3_ALERTA_PREFIX` — ignore; o contrato agora é o desta seção.

---

## 9. Fora do escopo — NÃO FAZER

- Alterar `bronze2silver.py`, `silver2gold.py`, `captura_servidor.py`, site ou schema do MySQL.
- Gerar `latest.json` em qualquer prefixo.
- Criar tabela de dedup ou gravar estado em disco/MySQL (watermark é em memória).
- Adicionar dependência Maven de parser de CSV.
- Parametrizar região AWS, criar `DB_PORT`, `JIRA_API_PATH`.
- Rodar `docker run --restart`, criar compose, CI ou registry privado.
- Refatorar classes, renomear métodos, criar testes automatizados no repo.
- Hardening de segurança, rotação de token, SSO, etc.

---

## 10. Resumo em uma frase

Trocar a entrada do `.jar` de `latest.json` pelos CSVs da `02-silver/metricas/`: listagem paginada,
watermark por servidor/lote, parser manual, rede por delta de bytes, janela de 10 min com média para
severidade e 7 de 10 amostras não consecutivas, aviso após 15 min sem dados — e atualizar apenas
`.env.example` e `README.md` dentro do repo `logs`.
