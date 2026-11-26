# Tutorial: Integrando Java com o Jira Cloud via API REST

## 🎯 Objetivo

Neste tutorial, vamos aprender a integrar uma aplicação Java com o **Jira Cloud** usando a API REST
para criar issues automaticamente. Vamos utilizar a classe `HttpClient` para realizar as requisições
HTTP e a biblioteca `Jackson` para converter (serializar) objetos Java em JSON.

A autenticação é feita via **token de API** combinado com o e-mail do usuário, um método seguro que
permite que aplicações externas façam requisições autenticadas sem armazenar senhas. O token funciona
como uma credencial especial que pode ser revogada a qualquer momento.

Para mais informações, consulte a
[documentação oficial da API do Jira](https://developer.atlassian.com/cloud/jira/platform/rest/v3/).

## 📚️ Dependências

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.2</version>
</dependency>
```

## Passo a Passo

### ⚒️ 1. Gerando um Token de API no Jira

Antes de integrar com o código Java, é preciso gerar um token de API:

1. Acesse [jira.atlassian.com](https://jira.atlassian.com) e crie uma conta (ou faça login).
2. Crie um site (workspace) e, dentro dele, crie um projeto (ex: tipo "Software", modelo "Kanban").
3. Copie a URL base do projeto, no formato `https://seu-dominio.atlassian.net`.
4. Clique na sua foto de perfil → "Configurações de perfil" → "Segurança" → "API tokens" →
   "Criar token de API".
5. Defina uma expiração para o token (recomendado por segurança) e copie o valor gerado —
   **ele não será exibido novamente**.

A **key do projeto** aparece na URL do backlog, por exemplo:
`https://java-integration.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog` → a key é
**`SCRUM`**.

### 🧠 2. O que é um DTO?

Um **JSON** é só texto — uma forma padronizada de representar dados (`{"nome": "Rick"}`). Só que em
Java não conseguimos manipular texto solto com segurança: não temos autocomplete, nem checagem de
tipos, nem como chamar `.getName()` num texto.

Por isso criamos uma classe Java cujos atributos têm os mesmos nomes das chaves do JSON. Essa classe
é chamada de **DTO** (*Data Transfer Object* — "objeto de transferência de dados"): ela não tem
regra de negócio nenhuma, só existe para carregar dados no formato do JSON, agora como um objeto
Java de verdade.

A biblioteca **Jackson** é quem faz a conversão nos dois sentidos:

- JSON → objeto Java: `mapper.readValue(json, MinhaClasseDto.class)`
- objeto Java → JSON: `mapper.writeValueAsString(meuObjeto)`

Ou seja: **o DTO é só um "molde" em Java para o formato do JSON.** Se o JSON muda, o DTO muda junto.

No caso do Jira, o JSON de criação de issue tem objetos dentro de objetos (`fields.project.key`,
`fields.issuetype.name`). Por isso, em vez de um DTO só, criamos vários DTOs pequenos —
`ProjectDto`, `IssueTypeDto`, `IssueFieldsDto` e `IssueRequestDto` — cada um espelhando um "nível"
do JSON.

### 🥸 3. Criando os DTOs da Requisição

Para representar o corpo JSON esperado pela API de criação de issues, criamos DTOs que espelham a
estrutura exigida pelo Jira:

```json
{
  "fields": {
    "project": { "key": "" },
    "summary": "",
    "issuetype": { "name": "" }
  }
}
```

* Nota: assim como no consumo de APIs, cada DTO precisa de um construtor vazio e getters/setters para
  que o Jackson consiga serializar o objeto corretamente.

```java
package school.sptech.dto;

public class IssueRequestDto {
    private IssueFieldsDto fields;
    // Construtor, getters e setters...
}
```

`IssueFieldsDto` agrega `ProjectDto` (com a `key` do projeto), o `summary` (título da issue) e
`IssueTypeDto` (com o `name` do tipo, ex: "Task", "Bug", "Story").

### 👉️ 4. Classe `Jira`

A classe `Jira` (em `school.sptech.config`) monta o header de autenticação `Basic` a partir do e-mail
e do token, e expõe o método `createIssue`:

```java
Jira jira = new Jira(baseUrl, email, apiToken);

String response = jira.createIssue(
      "SCRUM",                 // Key do projeto
      "Issue criada via Java", // Resumo
      "Task"                   // Tipo de issue
);

System.out.println(response);
```

**Explicação:**

1. Montamos os DTOs (`ProjectDto`, `IssueTypeDto`, `IssueFieldsDto`, `IssueRequestDto`) com os dados
   recebidos.
2. Serializamos `IssueRequestDto` para JSON com `objectMapper.writeValueAsString`.
3. Construímos a requisição `POST` para `{baseUrl}/rest/api/3/issue`, com o header `Authorization`
   contendo as credenciais em Base64.
4. Enviamos a requisição e retornamos o corpo da resposta caso o status esteja entre 200 e 299;
   caso contrário, lançamos uma exceção com o status e o corpo retornado.

### 🏃 5. Executando o Exemplo

Abra `src/main/java/school/sptech/ExemploCreateIssue.java` e substitua os valores de exemplo:

```java
String baseUrl = "https://seu-dominio.atlassian.net";
String email = "seu-email@example.com";
String apiToken = "seu-token-aqui";
```

Execute a classe `IntegracaoJiraSlack`. Se a requisição for bem-sucedida, uma nova issue será criada
no backlog do seu projeto e o JSON de resposta (com o ID da issue) será exibido no console.

### 6. Tratamento de Exceções

As operações HTTP e de serialização JSON podem gerar exceções (`IOException`, `InterruptedException`).
Neste exemplo, elas são tratadas em um bloco `try-catch`.

## ⚠️ Segurança

- **Nunca compartilhe seu token de API** publicamente ou suba no GitHub.
- **Nunca commite suas credenciais** no repositório.
- Se o token vazar, acesse imediatamente o Jira e delete/revogue o token, depois gere um novo.

## ➕️ Segue abaixo os links da documentação do Jackson, do HttpClient e do Jira:

- [HttpClient](https://www.baeldung.com/java-9-http-client)
- [Jackson](https://github.com/FasterXML/jackson-docs)
- [Documentação da API REST do Jira Cloud](https://developer.atlassian.com/cloud/jira/platform/rest/v3/)
- [Autenticação no Jira Cloud](https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/)
- [Criando Tokens de API](https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/)

## 📄 CSVs da silver (entrada dos alertas)

Não existe mais `latest.json`. A aplicação Java lê **diretamente os CSVs da camada silver**
(`02-silver/metricas/`, configurável em `S3_SILVER_PREFIX`). Cada servidor tem uma pasta por dia:

```text
02-silver/metricas/empresa_id=2/servidor_id=3/data=2026-11-24/20261124T224100Z_empresa-2_servidor-3_dados.csv
```

- Só arquivos `_dados.csv` são lidos; a silver não recebe `_processos.csv`.
- Só as partições `data=` de **hoje e ontem** (fuso de Brasília) são consideradas: o resto já
  estaria fora da janela de alerta.
- `empresa_id` e `servidor_id` são os ids do MySQL. O lote no nome do arquivo
  (`YYYYMMDDTHHMMSSZ`) é gerado no fuso de **Brasília**: o `Z` é enganoso, não é UTC.
- Um lote que cruza a meia-noite gera dois arquivos, com o mesmo lote e partições `data=` diferentes.
- O cabeçalho é o mesmo do agente:
  `id_empresa,id_servidor,username,data_hora,cpu,inte_cpu,ram_disponivel,ram_total,ram,disco,disco_disponivel,disco_total,bytes_enviados,bytes_recebidos,pacotes_enviados,pacotes_recebidos,trocas_contexto,temperatura_cpu,tempo_escrita_disco`.
- `data_hora` é a hora local de Brasília (`AAAA-MM-DD HH:MM:SS`), sem offset.
- `cpu`, `ram` e `disco` são percentuais. Campos faltando, vazios ou não numéricos viram
  "sem dados" — nunca zero.
- `bytes_enviados` e `bytes_recebidos` são **contadores cumulativos**. O Java calcula
  `Mbps = (bytes[t] - bytes[t-1]) / Δt * 8 / 1e6`, com o `Δt` real dos `data_hora` e delta
  negativo tratado como 0. O primeiro registro do lote usa a última linha do lote anterior.
- O tráfego de rede é somado por empresa (upload + download) e vira um chamado identificado
  apenas pela empresa, sem `servidor-{id}`.

## 🏷️ Labels enviadas ao Jira

Todo chamado sai com:

- `empresa-{id}` — usada pelo back-end do site para filtrar pela empresa logada;
- `servidor-{id}` — exceto no chamado de rede agregada;
- `componente-{cpu|ram|disco|rede}`;
- `severidade-{critico|atencao}`.

O título também repete os ids (`[EMP-2][SRV-9]`) e a descrição traz empresa, servidor,
média das amostras, limite, a leitura mais recente, a janela avaliada e quantas amostras
ficaram acima do limite.

O chamado só é criado quando a **média** das amostras da janela passa do limite escolhido
(`CRÍTICO` ou `ATENÇÃO`) e **7 de 10** amostras ficam acima dele — não precisam ser
consecutivas. A janela é de 10 minutos, contada a partir da leitura mais recente. Se já existir
chamado aberto com as mesmas labels, nada é criado de novo.

## ⚙️ Configuração por variável de ambiente

As variáveis são lidas do ambiente do processo (via `env_file` do compose ou `--env-file` do
Docker). O modelo está em `.env.example`; copie, preencha e **nunca** commite o arquivo com os
segredos. As credenciais AWS são opcionais: sem elas, o SDK usa a IAM Role da EC2.

| Variável | Padrão no código | Obrigatória? | Uso |
|---|---|---|---|
| `S3_BUCKET` | `teste-integracao-java` | não | bucket onde está a camada silver (use `s3-infratech-prod` no teste local) |
| `S3_SILVER_PREFIX` | `02-silver/metricas/` | não | prefixo dos CSVs da silver |
| `AWS_REGION` | `us-east-1` | não | região do S3 (aceita `AWS_DEFAULT_REGION` no boto3) |
| `JIRA_PROJECT` | `IFT` | não | projeto Jira — precisa ser igual ao `JIRA_PROJECT` do site |
| `JIRA_BASE_URL` | `https://sptech-infratech.atlassian.net` | não | URL base do Jira Cloud |
| `JIRA_EMAIL` | `felipe.msantos@sptech.school` | não | e-mail da conta que autentica na API |
| `JIRA_TOKEN` | `""` | **sim (segredo)** | token de API do Jira; sem ele a rodada é abortada |
| `SLACK_WEBHOOK` | `""` | **sim (segredo)** | webhook do canal; vazio = só cria no Jira e avisa no log |
| `DB_HOST` | `nome-do-container-mysql` | não | host/nome do container MySQL na rede Docker |
| `DB_DATABASE` | `infratech` | não | nome do banco |
| `DB_USER` | `aluno` | não | usuário do banco |
| `DB_PASSWORD` | `""` | **sim (segredo)** | senha do banco |
| `ALERTA_INTERVALO_SEGUNDOS` | `300` | não | intervalo entre rodadas do loop (mínimo 10) |
| `ALERTA_JANELA_MINUTOS` | `10` | não | janela avaliada, contada a partir da leitura mais recente |
| `ALERTA_MIN_AMOSTRAS` | `7` | não | amostras acima do limite para confirmar o chamado |
| `ALERTA_MAX_IDADE_MINUTOS` | `15` | não | sem dados novos por mais que isso: aviso no log, sem chamado |

`JIRA_TOKEN`, `SLACK_WEBHOOK` e `DB_PASSWORD` não têm valor padrão válido: são segredos e precisam
ser fornecidos em tempo de execução. A porta do MySQL continua fixa em `3306`.

### 🔁 Como a aplicação roda

O `main` cria as conexões (Jira, Slack, MySQL e S3) **uma vez** e entra em um loop: processa uma
rodada, dorme `ALERTA_INTERVALO_SEGUNDOS` e repete. Cada rodada lista a silver de forma paginada
e só baixa o lote mais recente de cada servidor (mais o anterior, para o primeiro delta de rede).
O watermark é **por empresa/servidor**, em memória, usando o lote do nome do arquivo: sem lote
novo, nenhum `GET` é feito. Um lote novo de um servidor não é ignorado por causa do lote mais novo
de outro. O container não usa `--restart`: se parar, não volta sozinho.

## 🏗️ Build do jar executável

```bash
mvn package
```

O `maven-shade-plugin` gera um **jar gordo** (com todas as dependências e `Main-Class`) em
`target/java-jira-integration-1.0-SNAPSHOT.jar`. É esse arquivo que o Dockerfile copia.

## 🐳 Imagem Docker e deploy na EC2

Build local e push no DockerHub (repositório **público**, sem segredos embutidos):

```bash
docker build -t seuusuario/infratech-alertas:1.0 .
docker push seuusuario/infratech-alertas:1.0
```

O compose com MySQL, site e alertas está no repositório do site, em
`deploy/docker-compose.yml`. Ele usa um único arquivo de ambiente compartilhado:
`/opt/infratech/infratech.env` (cópia local: `deploy/infratech.env`). O `DB_HOST`
é `mysql`, o nome do serviço na rede do compose.

Na EC2 (uma única vez, criar o arquivo de ambiente com o conteúdo documentado no
README do site):

```bash
sudo mkdir -p /opt/infratech
sudo tee /opt/infratech/infratech.env >/dev/null <<'EOF'
AMBIENTE_PROCESSO=producao
DB_HOST=mysql
DB_DATABASE=infratech
DB_USER=infratech_app
DB_PASSWORD=...
S3_BUCKET=s3-infratech-prod
S3_SILVER_PREFIX=02-silver/metricas/
AWS_REGION=us-east-1
JIRA_PROJECT=IFT
JIRA_BASE_URL=https://sptech-infratech.atlassian.net
JIRA_EMAIL=...
JIRA_TOKEN=...
SLACK_WEBHOOK=...
ALERTA_INTERVALO_SEGUNDOS=300
ALERTA_JANELA_MINUTOS=10
ALERTA_MIN_AMOSTRAS=7
ALERTA_MAX_IDADE_MINUTOS=15
EOF
sudo chmod 600 /opt/infratech/infratech.env
```

Para subir/atualizar (comandos executados em `/opt/infratech`, onde está o
compose copiado do repositório do site):

```bash
docker compose pull
docker compose up -d --force-recreate
docker compose logs -f alertas
```

Não use `alertas.env` nem `docker run --env-file` separadamente: o compose já
injeta o mesmo arquivo nos três serviços.

### ⚠️ Pré-requisitos e armadilhas

- **A role da EC2 precisa de `s3:ListBucket`** no bucket, com prefixo `02-silver/metricas*`,
  além de `s3:GetObject` em `02-silver/metricas/*`. Sem `ListBucket` a aplicação não descobre
  os lotes.
- As credenciais da AWS vêm da **IAM Role da EC2** (IMDSv2). Em container, a instância precisa de
  `HttpPutResponseHopLimit >= 2`, senão o SDK não alcança o `169.254.169.254`.
- A região do S3 vem de `AWS_REGION` (padrão `us-east-1`); `AWS_DEFAULT_REGION` também é aceito pelo boto3.
- Chamados antigos (criados antes das labels) não aparecem no filtro da dashboard; os novos
  começam já com `empresa-{id}`.
- Se a leitura mais recente ficar com mais de `ALERTA_MAX_IDADE_MINUTOS` (15 min) em relação ao
  relógio da máquina, a rodada só registra um aviso e **não** cria chamado. Mantenha NTP ativo.
- O `Z` no nome do lote não é UTC: é horário de Brasília. Um lote que cruza a meia-noite vira
  dois arquivos e os dois são lidos juntos.
