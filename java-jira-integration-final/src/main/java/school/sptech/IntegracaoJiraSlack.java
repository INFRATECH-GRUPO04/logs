package school.sptech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import school.sptech.config.Jira;
import school.sptech.config.Slack;
import java.util.List;

public class IntegracaoJiraSlack {

    public static void main(String[] args) {

        //Configurações e credenciais: -----------------------------------------------------

        // Dados de acesso ao Jira:
        String baseUrl = "https://sptech-infratech.atlassian.net";
        String email = "felipe.msantos@sptech.school";
        String apiToken = "ATATT3xFfGF0I8Xzs095ozmpyIk3EZ6U3qaMtfDhEB8jNYZ79vo9Q9SAdakh_-E42MWOwaNaH_ORYWxhl2B3EPbxd4mvsA3gHdfp99Cm59VChxas4eVOzcAbZUNHbHSAxOJy5dq_s1f71BCpqVaMmbtKZDbZRv36xZfgdS2wnYk5qV4P9NTsS7Y=BA8EE480";

        // URL do Webhook do Slack:
        String webhookUrl = "coloca aqui";

        // Dados do Amazon S3:
        String bucketName = "teste-integracao-java";
        String prefixo = "gold/alerta-java/";

        // Dados base para a issue do Jira:
        String projectKey = "IFT";
        String issueType = "Task";
        String priority = "High";

        try {
            //Inicia e cria as conexões entre os serviços: ----------------------------------------------

            // Jira e Slack
            Jira jira = new Jira(baseUrl, email, apiToken);
            Slack slack = new Slack(webhookUrl);

            // Banco de dados
            DatabaseConfig dbConfig = new DatabaseConfig();
            JdbcTemplate jdbcTemplate = dbConfig.getJdbcTemplate();

            // Amazon S3
            S3Provider s3Provider = new S3Provider();
            S3Service s3Service = new S3Service(s3Provider.getS3Client());


        //leitura dos dados tratados no S3: -------------------------------------------------------------
            System.out.println("Lendo dados do prefixo gold no Amazon S3");

            List<String> arquivos = s3Service.listarArquivos(bucketName, prefixo);

            // remove o nome da pasta da pesquisa:
            arquivos.removeIf(chave -> chave.equals(prefixo) || chave.endsWith("/"));

            if (arquivos.isEmpty()) {
                System.err.println("Nenhum arquivo encontrado no prefixo " + prefixo);
                return;
            }

            String ultimoArquivo = arquivos.get(arquivos.size() - 1);
            System.out.println("Arquivo selecionado: " + ultimoArquivo);

            String dadosGold = s3Service.lerArquivoGold(bucketName, ultimoArquivo);
            LeituraServidor leitura = extrairDadosDoServidor(dadosGold);

            if (leitura == null) {
                System.err.println("Erro: Não foi possível extrair dados válidos do arquivo");
                return;
            }

            String nomeServidor = leitura.getNomeServidor();
            System.out.println(String.format(" Servidor: %s | CPU: %.2f%% | RAM: %.2f%% | Disco: %.2f%% | Rede: %.2f Mbps",
                    nomeServidor, leitura.getCpuAtual(), leitura.getRamAtual(), leitura.getDiscoAtual(), leitura.getRedeAtual()));


            // Verificação dos limites criticos no BD: ----------------------------------------------------------
            System.out.println("\nConsultando limites no MySQL para o servidor " + nomeServidor);

            LimitesComponente limitesCpu = buscarLimitesComponente(jdbcTemplate, nomeServidor, "cpu");
            LimitesComponente limitesRam = buscarLimitesComponente(jdbcTemplate, nomeServidor, "ram");
            LimitesComponente limitesDisco = buscarLimitesComponente(jdbcTemplate, nomeServidor, "disco");
            LimitesComponente limitesRede = buscarLimitesComponente(jdbcTemplate, nomeServidor, "rede");

            // Disparo dos alertas: ------------------------------------------------------------------
            System.out.println("\nValidando limites e processando alertas...");

            disparoAlerta(jira, slack, baseUrl, projectKey, issueType, priority, nomeServidor, "CPU", leitura.getCpuAtual(), limitesCpu);
            disparoAlerta(jira, slack, baseUrl, projectKey, issueType, priority, nomeServidor, "RAM", leitura.getRamAtual(), limitesRam);
            disparoAlerta(jira, slack, baseUrl, projectKey, issueType, priority, nomeServidor, "Disco", leitura.getDiscoAtual(), limitesDisco);
            disparoAlerta(jira, slack, baseUrl, projectKey, issueType, priority, nomeServidor, "Rede", leitura.getRedeAtual(), limitesRede);

        } catch (Exception e) {
            System.err.println("Erro durante a execução do fluxo de integração:");
            e.printStackTrace();
        }
    }

    //Classe DTO para transportar os dados extraídos do arquivo atraves dos metodos, permitindo
    // retornar a classe inteira e pegando cada atributo com o get(), porque o
    // java so permite retornar um objeto por vez: ---------------------------------------------------------

    public static class LeituraServidor {
        private final String nomeServidor;
        private final Double cpuAtual;
        private final Double ramAtual;
        private final Double discoAtual;
        private final Double redeAtual;

        public LeituraServidor(String nomeServidor, Double cpuAtual, Double ramAtual, Double discoAtual, Double redeAtual) {
            this.nomeServidor = nomeServidor;
            this.cpuAtual = cpuAtual;
            this.ramAtual = ramAtual;
            this.discoAtual = discoAtual;
            this.redeAtual = redeAtual;
        }

        public String getNomeServidor() {
            return nomeServidor;
        }
        public Double getCpuAtual() {
            return cpuAtual;
        }
        public Double getRamAtual() {
            return ramAtual;
        }
        public Double getDiscoAtual() {
            return discoAtual;
        }
        public Double getRedeAtual() {
            return redeAtual;
        }
    }

    // Pega a captura do arquivo JSON e extrai os dados:
    private static LeituraServidor extrairDadosDoServidor(String conteudoJson) {
        if (conteudoJson == null || conteudoJson.isBlank()) {
            return null;
        }

        try {
            // ObjectMapper -> pega textos em JSON e converte para objetos Java
            ObjectMapper mapper = new ObjectMapper();

            // transforma o texto bruto do S3 em uma árvore JSON que o Java entende
            JsonNode traducaoJsonEmArvore = mapper.readTree(conteudoJson);

            // verifica se o JSON principal é um Objeto {}
            if (traducaoJsonEmArvore.isObject()) {

                // vai até o bloco "kpis" para extrair a rede
                JsonNode kpis = traducaoJsonEmArvore.path("kpis");
                Double redeAtual = kpis.path("trafego_mbps").asDouble();

                // vai até a lista de "servidores" e pega o primeiro servidor
                JsonNode servidores = traducaoJsonEmArvore.path("servidores");

                if (servidores.isArray() && !servidores.isEmpty()) {
                    JsonNode servidorAlvo = servidores.get(0);

                    String nomeServidor = servidorAlvo.path("nome").asText();

                    JsonNode metricasAtuais = servidorAlvo.path("metricas_atuais");

                    // busca os valores de CPU, RAM e Disco pelas chaves
                    Double cpuAtual = metricasAtuais.path("cpu_pct").asDouble();
                    Double ramAtual = metricasAtuais.path("ram_pct").asDouble();
                    Double discoAtual = metricasAtuais.path("disco_pct").asDouble();

                    // devolve os dados empacotados no DTO
                    return new LeituraServidor(nomeServidor, cpuAtual, ramAtual, discoAtual, redeAtual);
                }
            }
        } catch (Exception e) {
            System.err.println("Erro ao interpretar o arquivo JSON do S3: " + e.getMessage());
        }

        return null;
    }

    public static class LimitesComponente {
        private final Double limiteAtencao;
        private final Double limiteCritico;

        public LimitesComponente(Double limiteAtencao, Double limiteCritico) {
            this.limiteAtencao = limiteAtencao;
            this.limiteCritico = limiteCritico;
        }

        public Double getLimiteAtencao() { return limiteAtencao; }
        public Double getLimiteCritico() { return limiteCritico; }
    }

    private static LimitesComponente buscarLimitesComponente(JdbcTemplate jdbcTemplate, String nomeServidor, String tipoComponente) {
        String sqlLimit = "SELECT m.limite_atencao, m.limite_critico FROM metrica m " +
                "JOIN componente c ON m.fk_componente = c.id_componente " +
                "JOIN servidor s ON c.fk_servidor = s.id_servidor " +
                "WHERE s.nome = ? AND c.tipo = ? LIMIT 1";
        try {
            // RowMapper -> traduz o resultado do banco para o objeto
            return jdbcTemplate.queryForObject(sqlLimit, (rs, rowNum) -> {
                Double atencao = rs.getObject("limite_atencao", Double.class);
                Double critico = rs.getObject("limite_critico", Double.class);
                return new LimitesComponente(atencao, critico);
            }, nomeServidor, tipoComponente);
        } catch (Exception e) {
            System.err.println("Aviso: Limites não cadastrados no MySQL para o servidor: " + nomeServidor);
            return null;
        }
    }

    private static void disparoAlerta(
            Jira jira, Slack slack, String baseUrl, String projectKey, String issueType,
            String priority, String nomeServidor, String nomeComponente,
            Double valorAtual, LimitesComponente limites
    ) {
        if (limites == null) {
            System.out.println(String.format("Validação ignorada para %s: limites não encontrados.", nomeComponente));
            return;
        }

        String severidade = null;
        Double limiteUltrapassado = null;
        String prioridadeTicket = priority;

        //verifica primeiro se ultrapassou o CRÍTICO
        if (limites.getLimiteCritico() != null && valorAtual >= limites.getLimiteCritico()) {
            severidade = "CRÍTICO";
            limiteUltrapassado = limites.getLimiteCritico();
            prioridadeTicket = "Highest";

            //se não for crítico, verifica se ultrapassou ATENÇÃO
        } else if (limites.getLimiteAtencao() != null && valorAtual >= limites.getLimiteAtencao()) {
            severidade = "ATENÇÃO";
            limiteUltrapassado = limites.getLimiteAtencao();
        }

        // dispara o alerta apenas se ultrapassar
        if (severidade != null) {
            System.out.println(String.format("\nALERTA %s: Limite de %s excedido! Disparando notificações...", severidade, nomeComponente));

            String summary = String.format("[%s] %s acima do limite - %s", severidade, nomeComponente, nomeServidor);
            String description = String.format(
                    "Servidor %s apresentou utilização de %s em estado %s. Valor atual: %.2f%% | Limite configurado: %.2f%%",
                    nomeServidor, nomeComponente, severidade, valorAtual, limiteUltrapassado
            );

            try {
                String response = jira.createIssue(projectKey, summary, description, issueType, prioridadeTicket);

                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode issue = objectMapper.readTree(response);
                String issueKey = issue.get("key").asText();
                String issueUrl = baseUrl + "/browse/" + issueKey;

                slack.sendIssueCreatedMessage(issueKey, summary, issueType, issueUrl);
                System.out.println("Alertas enviados com sucesso para " + severidade + "!");

            } catch (Exception e) {
                System.err.println("Erro ao disparar alerta: " + e.getMessage());
            }
        } else {
            System.out.println(String.format("%s (%s): Operando normalmente (%.2f%%)", nomeServidor, nomeComponente, valorAtual));
        }
    }
}
