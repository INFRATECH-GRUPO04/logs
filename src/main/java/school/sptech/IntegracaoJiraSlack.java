package school.sptech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import school.sptech.config.Jira;
import school.sptech.config.Slack;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class IntegracaoJiraSlack {

    // Janela de avaliação contada a partir da leitura mais recente (maior data_hora).
    private static final long JANELA_MS = configLong("ALERTA_JANELA_MINUTOS", 10) * 60_000L;

    // Quantidade mínima de amostras acima do limite para confirmar o chamado.
    private static final long MIN_AMOSTRAS = configLong("ALERTA_MIN_AMOSTRAS", 7);

    // Mesmo com a janela de 10 min, o lote pode ter mais linhas até o agente passar a
    // enviar 10 capturas por lote: avalia sempre as 10 mais recentes.
    private static final int AMOSTRAS_CONSIDERADAS = 10;

    // Watermark por (empresa, servidor), usando o lote do nome do arquivo.
    private static final Map<String, String> ultimoLotePorServidor = new HashMap<>();
    private static Instant ultimaReferencia = null;
    private static boolean avisoStaleAtivo = false;

    public static void main(String[] args) {

        //Configurações e credenciais: -----------------------------------------------------

        // Dados de acesso ao Jira:
        String baseUrl = System.getenv().getOrDefault("JIRA_BASE_URL",
                "https://sptech-infratech.atlassian.net");
        String email = System.getenv().getOrDefault("JIRA_EMAIL", "felipe.msantos@sptech.school");
        String apiToken = System.getenv().getOrDefault("JIRA_TOKEN", "");

        // URL do Webhook do Slack:
        String webhookUrl = System.getenv().getOrDefault("SLACK_WEBHOOK", "");

        // Dados do Amazon S3. O prefixo aponta para os CSVs da camada silver:
        String bucketName = System.getenv().getOrDefault("S3_BUCKET", "teste-integracao-java");
        String prefixo = System.getenv().getOrDefault("S3_SILVER_PREFIX", "02-silver/metricas/");
        if (!prefixo.endsWith("/")) {
            prefixo += "/";
        }

        // Dados base para a issue do Jira (tudo continua indo para o mesmo projeto):
        String projectKey = System.getenv().getOrDefault("JIRA_PROJECT", "IFT");
        String issueType = "Task";
        String priority = "High";

        //Inicia e cria as conexões entre os serviços (uma vez só, fora do loop): --------------------

        // Jira e Slack
        Jira jira = new Jira(baseUrl, email, apiToken);
        Slack slack = new Slack(webhookUrl);

        // Banco de dados
        DatabaseConfig dbConfig = new DatabaseConfig();
        JdbcTemplate jdbcTemplate = dbConfig.getJdbcTemplate();

        // Amazon S3
        S3Provider s3Provider = new S3Provider();
        S3Service s3Service = new S3Service(s3Provider.getS3Client());
        SilverLeituraService silverService = new SilverLeituraService(s3Service);

        long intervalo = intervaloMs();

        // Fica rodando sempre: processa uma rodada e dorme o intervalo configurado.
        while (true) {
            try {
                processarRodada(jira, slack, jdbcTemplate, silverService, baseUrl, bucketName,
                        prefixo, projectKey, issueType, priority, apiToken);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Erro na rodada: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                Thread.sleep(intervalo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // Executa uma rodada completa: lê os CSVs da silver, confere os limites e dispara os alertas.
    private static void processarRodada(Jira jira, Slack slack, JdbcTemplate jdbcTemplate,
                                        SilverLeituraService silverService, String baseUrl,
                                        String bucketName, String prefixo, String projectKey,
                                        String issueType, String priority, String apiToken)
            throws Exception {

        if (apiToken == null || apiToken.isBlank()) {
            System.err.println("JIRA_TOKEN não configurado; rodada abortada.");
            return;
        }

        //leitura dos CSVs da silver: -------------------------------------------------------------

        System.out.println("Lendo CSVs de " + prefixo + " no bucket " + bucketName);

        SilverLeituraService.LeituraSilver leitura =
                silverService.ler(bucketName, prefixo, ultimoLotePorServidor);

        // Se não chega dado novo há mais de 15 min, avisa uma vez e não cria chamado.
        // Essa checagem vem ANTES do watermark: sem ela a rodada sairia calada.
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

        long agora = referencia.toEpochMilli();

        // Verificação dos limites e disparo por servidor: ------------------------------------------

        for (LeituraServidor servidor : leitura.servidores) {
            System.out.println(String.format("\nServidor: %s | Empresa: %d | Id do servidor: %d",
                    servidor.nome, servidor.idEmpresa, servidor.idServidor));

            verificarComponente(jira, slack, jdbcTemplate, baseUrl, projectKey, issueType, priority,
                    servidor, "CPU", "cpu", amostra -> amostra.cpuPct, agora);
            verificarComponente(jira, slack, jdbcTemplate, baseUrl, projectKey, issueType, priority,
                    servidor, "RAM", "ram", amostra -> amostra.ramPct, agora);
            verificarComponente(jira, slack, jdbcTemplate, baseUrl, projectKey, issueType, priority,
                    servidor, "Disco", "disco", amostra -> amostra.discoPct, agora);
        }

        // Rede é agregada da empresa: soma o tráfego de todos os servidores no mesmo instante. -----

        Map<Integer, Map<Long, Double>> trafegoPorEmpresa = new HashMap<>();
        Map<Integer, Double> capacidadePorEmpresa = new HashMap<>();
        Map<Integer, LimitesComponente> limitesRedePorEmpresa = new HashMap<>();

        for (LeituraServidor servidor : leitura.servidores) {
            LimitesComponente limites = buscarLimites(jdbcTemplate, servidor.idServidor, "rede");
            if (limites != null) {
                limitesRedePorEmpresa.putIfAbsent(servidor.idEmpresa, limites);
            }

            Double capacidade = buscarCapacidadeRede(jdbcTemplate, servidor.idServidor);
            if (capacidade != null && capacidade > 0) {
                capacidadePorEmpresa.merge(servidor.idEmpresa, capacidade, Double::sum);
            }

            for (Amostra amostra : servidor.amostras) {
                if (amostra.uploadMbps == null || amostra.downloadMbps == null) {
                    continue;
                }
                Long instante = timestamp(amostra.dataHora);
                if (instante == null) {
                    continue;
                }
                trafegoPorEmpresa.computeIfAbsent(servidor.idEmpresa, chaveMap -> new HashMap<>())
                        .merge(instante, amostra.uploadMbps + amostra.downloadMbps, Double::sum);
            }
        }

        for (Map.Entry<Integer, Map<Long, Double>> entrada : trafegoPorEmpresa.entrySet()) {
            int idEmpresa = entrada.getKey();
            LimitesComponente limites = limitesRedePorEmpresa.get(idEmpresa);

            if (limites == null) {
                System.err.println("Aviso: limites de rede não cadastrados para a empresa " + idEmpresa);
                continue;
            }

            List<Ponto> pontos = new ArrayList<>();
            for (Map.Entry<Long, Double> ponto : entrada.getValue().entrySet()) {
                double valor = ponto.getValue();

                // Se o limite estiver em %, converte o tráfego usando a capacidade do link.
                if (limites.unidade != null && limites.unidade.contains("%")) {
                    Double capacidade = capacidadePorEmpresa.get(idEmpresa);
                    if (capacidade == null || capacidade <= 0) {
                        continue;
                    }
                    valor = valor / capacidade * 100;
                }
                pontos.add(new Ponto(ponto.getKey(), valor));
            }

            Resultado resultado = avaliar(pontos, limites, agora);
            disparoAlerta(jira, slack, baseUrl, projectKey, issueType, priority, idEmpresa, null,
                    null, "Rede agregada da empresa", "rede", resultado, limites);
        }

        // Marca o watermark por servidor só depois que a rodada terminou inteira.
        ultimoLotePorServidor.putAll(leitura.lotesProcessados);
    }

    // Intervalo entre rodadas em milissegundos (mínimo de 10 segundos).
    private static long intervaloMs() {
        try {
            long segundos = Long.parseLong(
                    System.getenv().getOrDefault("ALERTA_INTERVALO_SEGUNDOS", "300"));
            return Math.max(segundos, 10) * 1000L;
        } catch (Exception e) {
            return 300_000L;
        }
    }

    // Lê uma configuração numérica sem quebrar a classe se o valor estiver inválido.
    private static long configLong(String nome, long padrao) {
        try {
            return Long.parseLong(System.getenv().getOrDefault(nome, String.valueOf(padrao)));
        } catch (Exception e) {
            return padrao;
        }
    }

    // Classe DTO para transportar os dados extraídos do CSV atraves dos metodos, permitindo
    // retornar a classe inteira e pegando cada atributo com o get(), porque o
    // java so permite retornar um objeto por vez: ---------------------------------------------------------

    public static class Amostra {
        private final String dataHora;
        private final Double cpuPct;
        private final Double ramPct;
        private final Double discoPct;
        private final Double uploadMbps;
        private final Double downloadMbps;

        public Amostra(String dataHora, Double cpuPct, Double ramPct, Double discoPct,
                       Double uploadMbps, Double downloadMbps) {
            this.dataHora = dataHora;
            this.cpuPct = cpuPct;
            this.ramPct = ramPct;
            this.discoPct = discoPct;
            this.uploadMbps = uploadMbps;
            this.downloadMbps = downloadMbps;
        }
    }

    public static class LeituraServidor {
        private final int idEmpresa;
        private final int idServidor;
        private final String nome;
        private final List<Amostra> amostras;

        public LeituraServidor(int idEmpresa, int idServidor, String nome, List<Amostra> amostras) {
            this.idEmpresa = idEmpresa;
            this.idServidor = idServidor;
            this.nome = nome;
            this.amostras = amostras;
        }
    }

    public static class LimitesComponente {
        private final Double limiteAtencao;
        private final Double limiteCritico;
        private final String unidade;

        public LimitesComponente(Double limiteAtencao, Double limiteCritico, String unidade) {
            this.limiteAtencao = limiteAtencao;
            this.limiteCritico = limiteCritico;
            this.unidade = unidade;
        }
    }

    // Uma amostra já convertida para instante + valor, usada tanto pelos componentes
    // dos servidores quanto pelo tráfego agregado da empresa.
    public static class Ponto {
        private final long instante;
        private final double valor;

        public Ponto(long instante, double valor) {
            this.instante = instante;
            this.valor = valor;
        }
    }

    public static class Resultado {
        private final String estado;
        private final Double media;
        // Quantidade de amostras acima do limite escolhido.
        private final int amostras;
        private final boolean confirmado;
        // Instante e quantidade total de amostras avaliadas, usados na descrição do Jira.
        private final long ultimaLeitura;
        private final int total;

        public Resultado(String estado, Double media, int amostras, boolean confirmado,
                         long ultimaLeitura, int total) {
            this.estado = estado;
            this.media = media;
            this.amostras = amostras;
            this.confirmado = confirmado;
            this.ultimaLeitura = ultimaLeitura;
            this.total = total;
        }
    }

    // O CSV grava data_hora como hora local de Brasília, sem offset. Também aceita ISO com Z.
    static Long timestamp(String dataHora) {
        if (dataHora == null || dataHora.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(dataHora).toEpochMilli();
        } catch (Exception ignored) {
            try {
                // O agente grava hora local do servidor (sem offset) no formato AAAA-MM-DD HH:MM:SS.
                return LocalDateTime.parse(dataHora.replace(' ', 'T'))
                        .atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli();
            } catch (Exception e) {
                return null;
            }
        }
    }

    // Limites vêm do MySQL pelo id do servidor, nunca pelo nome (nome pode se repetir entre empresas).
    private static LimitesComponente buscarLimites(JdbcTemplate jdbcTemplate, int idServidor, String tipo) {
        LimitesComponente limites = consultarLimites(jdbcTemplate, idServidor, tipo, "uso_percentual", null);

        // Na rede o seed usa uma métrica em Mbps; se não houver percentual, tenta Mbps.
        if (limites == null && "rede".equals(tipo)) {
            limites = consultarLimites(jdbcTemplate, idServidor, tipo, null, "Mbps");
        }

        return limites;
    }

    private static LimitesComponente consultarLimites(JdbcTemplate jdbcTemplate, int idServidor,
                                                      String tipo, String nomeMetrica, String unidade) {
        String sql = "SELECT m.limite_atencao, m.limite_critico, m.unidade FROM metrica m " +
                "JOIN componente c ON m.fk_componente = c.id_componente " +
                "WHERE c.fk_servidor = ? AND c.tipo = ? AND m.status_monitoramento = 1 " +
                "AND m.limite_atencao IS NOT NULL AND m.limite_critico IS NOT NULL";

        List<Object> parametros = new ArrayList<>();
        parametros.add(idServidor);
        parametros.add(tipo);

        if (nomeMetrica != null) {
            sql += " AND m.nome = ?";
            parametros.add(nomeMetrica);
        }
        if (unidade != null) {
            sql += " AND m.unidade = ?";
            parametros.add(unidade);
        }

        sql += " ORDER BY m.id_metrica LIMIT 1";

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new LimitesComponente(
                    rs.getDouble("limite_atencao"),
                    rs.getDouble("limite_critico"),
                    rs.getString("unidade")), parametros.toArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static Double buscarCapacidadeRede(JdbcTemplate jdbcTemplate, int idServidor) {
        String sql = "SELECT capacidade_total FROM componente WHERE fk_servidor = ? AND tipo = 'rede' LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, Double.class, idServidor);
        } catch (Exception e) {
            return null;
        }
    }

    private static void verificarComponente(
            Jira jira, Slack slack, JdbcTemplate jdbcTemplate, String baseUrl, String projectKey,
            String issueType, String priority, LeituraServidor servidor, String nomeComponente,
            String tipoComponente, Function<Amostra, Double> extrator, long referencia
    ) {
        LimitesComponente limites = buscarLimites(jdbcTemplate, servidor.idServidor, tipoComponente);

        if (limites == null) {
            System.err.println("Aviso: limites não cadastrados para " + nomeComponente
                    + " no servidor " + servidor.idServidor);
            return;
        }

        List<Ponto> pontos = new ArrayList<>();
        for (Amostra amostra : servidor.amostras) {
            Double valor = extrator.apply(amostra);
            Long instante = timestamp(amostra.dataHora);
            if (valor != null && instante != null) {
                pontos.add(new Ponto(instante, valor));
            }
        }

        Resultado resultado = avaliar(pontos, limites, referencia);
        disparoAlerta(jira, slack, baseUrl, projectKey, issueType, priority, servidor.idEmpresa,
                servidor.idServidor, servidor.nome, nomeComponente, tipoComponente, resultado, limites);
    }

    // Avalia a janela: as 10 amostras mais recentes dentro de ALERTA_JANELA_MINUTOS.
    // A média define a severidade e o chamado só sai com ALERTA_MIN_AMOSTRAS acima do
    // limite escolhido — não precisam ser consecutivas.
    private static Resultado avaliar(List<Ponto> pontos, LimitesComponente limites, long referencia) {
        List<Ponto> validos = new ArrayList<>();
        for (Ponto ponto : pontos) {
            if (ponto.instante <= referencia && referencia - ponto.instante <= JANELA_MS) {
                validos.add(ponto);
            }
        }
        validos.sort(Comparator.comparingLong(ponto -> ponto.instante));

        if (validos.size() > AMOSTRAS_CONSIDERADAS) {
            validos = new ArrayList<>(
                    validos.subList(validos.size() - AMOSTRAS_CONSIDERADAS, validos.size()));
        }

        if (validos.isEmpty()) {
            return new Resultado("SEM_DADOS", null, 0, false, 0, 0);
        }

        Ponto ultima = validos.get(validos.size() - 1);
        Double media = validos.stream().mapToDouble(ponto -> ponto.valor).average().orElse(0);
        double alvo;
        String estado;

        if (limites.limiteCritico != null && media >= limites.limiteCritico) {
            estado = "CRITICO";
            alvo = limites.limiteCritico;
        } else if (limites.limiteAtencao != null && media >= limites.limiteAtencao) {
            estado = "ATENCAO";
            alvo = limites.limiteAtencao;
        } else {
            return new Resultado("NORMAL", media, 0, false, ultima.instante, validos.size());
        }

        int acima = 0;
        for (Ponto ponto : validos) {
            if (ponto.valor >= alvo) {
                acima++;
            }
        }

        boolean confirmado = acima >= MIN_AMOSTRAS;
        return new Resultado(estado, media, acima, confirmado, ultima.instante, validos.size());
    }

    private static void disparoAlerta(
            Jira jira, Slack slack, String baseUrl, String projectKey, String issueType,
            String priority, int idEmpresa, Integer idServidor, String nomeServidor,
            String nomeComponente, String tipoComponente, Resultado resultado, LimitesComponente limites
    ) {
        if (resultado == null || !resultado.confirmado
                || "NORMAL".equals(resultado.estado) || "SEM_DADOS".equals(resultado.estado)) {
            System.out.println(String.format("%s: %s (sem chamado)", nomeComponente,
                    resultado == null ? "sem dados" : resultado.estado));
            return;
        }

        boolean critico = "CRITICO".equals(resultado.estado);
        String severidade = critico ? "CRÍTICO" : "ATENÇÃO";
        String prioridadeTicket = critico ? "Highest" : priority;
        Double limite = critico ? limites.limiteCritico : limites.limiteAtencao;
        String unidade = limites.unidade == null ? "" : limites.unidade;
        long janelaMin = configLong("ALERTA_JANELA_MINUTOS", 10);

        String summary = idServidor == null
                ? String.format("[EMP-%d] [%s] %s acima do limite", idEmpresa, severidade, nomeComponente)
                : String.format("[EMP-%d][SRV-%d] [%s] %s acima do limite - %s",
                        idEmpresa, idServidor, severidade, nomeComponente, nomeServidor);

        String servidorTexto = idServidor == null
                ? "agregado da empresa " + idEmpresa
                : nomeServidor + " (id " + idServidor + ")";

        String leituraTexto = "sem leitura";
        if (resultado.ultimaLeitura > 0) {
            leituraTexto = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(resultado.ultimaLeitura),
                            ZoneId.of("America/Sao_Paulo"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        String description = String.format(
                "Empresa: id %d%nServidor: %s%nComponente: %s%nEstado: %s%n" +
                        "Média das amostras: %.2f%s%nLimite configurado: %.2f%s%n" +
                        "Leitura mais recente: %s%n" +
                        "Janela avaliada: %d min (%d de %d amostras acima do limite)%n" +
                        "Confirmado por %d amostras em até %d minutos.",
                idEmpresa, servidorTexto, nomeComponente, severidade, resultado.media, unidade,
                limite, unidade, leituraTexto, janelaMin, resultado.amostras, resultado.total,
                resultado.amostras, janelaMin);

        // Antes de criar, verifica se já existe chamado aberto para essa mesma ocorrência.
        List<String> filtro = new ArrayList<>();
        filtro.add("empresa-" + idEmpresa);
        if (idServidor != null) {
            filtro.add("servidor-" + idServidor);
        }
        filtro.add("componente-" + tipoComponente);

        try {
            if (jira.existeChamadoAberto(projectKey, filtro)) {
                System.out.println("Chamado já aberto para " + nomeComponente + " - " + servidorTexto
                        + ". Não vai gerar outro.");
                return;
            }

            // Labels que o back-end do site usa para filtrar por empresa e identificar o servidor.
            List<String> labels = new ArrayList<>(filtro);
            labels.add("severidade-" + (critico ? "critico" : "atencao"));

            String response = jira.createIssue(projectKey, summary, description, issueType,
                    prioridadeTicket, labels);

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode issue = objectMapper.readTree(response);
            String issueKey = issue.get("key").asText();
            String issueUrl = baseUrl + "/browse/" + issueKey;

            String contexto = idServidor == null
                    ? "Empresa #" + idEmpresa
                    : "Empresa #" + idEmpresa + " · Servidor #" + idServidor;

            slack.sendIssueCreatedMessage(issueKey, summary, issueType, issueUrl, contexto);
            System.out.println("Alerta " + severidade + " enviado: " + issueKey);

        } catch (Exception e) {
            System.err.println("Erro ao disparar alerta: " + e.getMessage());
        }
    }
}
