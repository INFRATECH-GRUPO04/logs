package school.sptech;

import school.sptech.IntegracaoJiraSlack.Amostra;
import school.sptech.IntegracaoJiraSlack.LeituraServidor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Lê os CSVs da camada silver (02-silver/metricas/) e converte cada lote nas amostras
// que a lógica de alertas consome. Não existe mais latest.json: o Java faz a conversão.
public class SilverLeituraService {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMATO_LOTE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    // Chave relativa ao prefixo, por exemplo:
    // empresa_id=2/servidor_id=3/data=2026-11-24/20261124T224100Z_empresa-2_servidor-3_dados.csv
    private static final Pattern CHAVE = Pattern.compile(
            "^empresa_id=(\\d+)/servidor_id=(\\d+)/data=(\\d{4}-\\d{2}-\\d{2})/([^/]+)$");
    private static final Pattern NOME = Pattern.compile(
            "^(\\d{8}T\\d{6}Z)_empresa-\\d+_servidor-\\d+_dados\\.csv$");
    private static final Pattern DATA_PARTICAO = Pattern.compile("data=(\\d{4}-\\d{2}-\\d{2})/");

    private final S3Service s3Service;

    public SilverLeituraService(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    // Lista a silver, baixa só o lote mais recente de cada servidor (e o anterior, para o
    // primeiro delta de rede) e devolve as amostras já convertidas.
    public LeituraSilver ler(String bucketName, String prefixo,
                             Map<String, String> ultimoLotePorServidor) {

        LeituraSilver leitura = new LeituraSilver();
        Map<String, ChavesServidor> porServidor = new HashMap<>();

        // Dados de mais de dois dias já estariam fora da janela: nem vale baixar.
        LocalDate hoje = LocalDate.now(BRASILIA);
        LocalDate ontem = hoje.minusDays(1);

        for (String chaveCompleta : s3Service.listarArquivos(bucketName, prefixo)) {
            if (!chaveCompleta.endsWith("_dados.csv")) {
                continue;
            }
            if (!chaveCompleta.startsWith(prefixo)) {
                continue;
            }

            String relativa = chaveCompleta.substring(prefixo.length());
            Matcher casamento = CHAVE.matcher(relativa);
            if (!casamento.matches()) {
                System.err.println("Aviso: chave fora do padrão da silver ignorada: " + chaveCompleta);
                continue;
            }

            int idEmpresa = Integer.parseInt(casamento.group(1));
            int idServidor = Integer.parseInt(casamento.group(2));
            String nomeArquivo = casamento.group(4);

            if (idEmpresa <= 0 || idServidor <= 0) {
                System.err.println("Aviso: chave com empresa/servidor inválido ignorada: " + chaveCompleta);
                continue;
            }

            LocalDate particao;
            try {
                particao = LocalDate.parse(casamento.group(3));
            } catch (Exception e) {
                continue;
            }
            if (particao.isBefore(ontem) || particao.isAfter(hoje)) {
                continue;
            }

            Matcher nome = NOME.matcher(nomeArquivo);
            if (!nome.matches()) {
                System.err.println("Aviso: nome de arquivo fora do padrão ignorado: " + chaveCompleta);
                continue;
            }

            String lote = nome.group(1);
            ChavesServidor chaves = porServidor.computeIfAbsent(
                    idEmpresa + "-" + idServidor,
                    chave -> new ChavesServidor(idEmpresa, idServidor));
            chaves.chavesPorLote.computeIfAbsent(lote, chave -> new ArrayList<>()).add(chaveCompleta);
        }

        long maiorInstante = Long.MIN_VALUE;

        for (ChavesServidor chaves : porServidor.values()) {
            List<String> lotes = new ArrayList<>(chaves.chavesPorLote.keySet());
            // O lote é um timestamp de Brasília: o mais recente fica no topo.
            lotes.sort(Comparator.comparing(SilverLeituraService::instanteLote).reversed());
            if (lotes.isEmpty()) {
                continue;
            }

            String loteAtual = lotes.get(0);
            if (loteAtual.equals(ultimoLotePorServidor.get(chaves.chave()))) {
                continue; // sem lote novo para este servidor: não faz GET
            }

            // Lote imediatamente anterior: serve só para calcular o 1º delta de rede.
            String loteAnterior = lotes.size() > 1 ? lotes.get(1) : null;

            try {
                LinhaCsv base = loteAnterior == null ? null
                        : ultimaLinha(bucketName, chaves.chavesPorLote.get(loteAnterior));

                List<LinhaCsv> linhas = new ArrayList<>();
                for (String chave : chaves.chavesPorLote.get(loteAtual)) {
                    linhas.addAll(lerLinhas(bucketName, chave));
                }
                if (linhas.isEmpty()) {
                    continue;
                }
                // Ordena por data_hora antes de calcular deltas e antes de avaliar.
                Collections.sort(linhas, Comparator.comparingLong(linha -> linha.instante));

                String nomeServidor = null;
                for (LinhaCsv linha : linhas) {
                    if (linha.username != null && !linha.username.isBlank()) {
                        nomeServidor = linha.username;
                        break;
                    }
                }
                if (nomeServidor == null) {
                    nomeServidor = "Servidor " + chaves.idServidor;
                }

                List<Amostra> amostras = new ArrayList<>();
                LinhaCsv anterior = base;
                for (LinhaCsv atual : linhas) {
                    Double upload = null;
                    Double download = null;

                    if (anterior != null && anterior.instante < atual.instante) {
                        long segundos = Math.max(1, (atual.instante - anterior.instante) / 1000);
                        upload = mbps(atual.bytesEnviados, anterior.bytesEnviados, segundos);
                        download = mbps(atual.bytesRecebidos, anterior.bytesRecebidos, segundos);
                    }

                    amostras.add(new Amostra(atual.dataHora, atual.cpu, atual.ram, atual.disco,
                            upload, download));
                    anterior = atual;
                }

                leitura.servidores.add(new LeituraServidor(chaves.idEmpresa, chaves.idServidor,
                        nomeServidor, amostras));
                leitura.lotesProcessados.put(chaves.chave(), loteAtual);

                long ultima = linhas.get(linhas.size() - 1).instante;
                if (ultima > maiorInstante) {
                    maiorInstante = ultima;
                }

                System.out.println("Lote " + loteAtual + " carregado: " + chaves.chave()
                        + " (" + amostras.size() + " amostras)");
            } catch (Exception e) {
                System.err.println("Erro ao ler o lote " + loteAtual + " do servidor "
                        + chaves.chave() + ": " + e.getMessage());
            }
        }

        if (maiorInstante != Long.MIN_VALUE) {
            leitura.referencia = Instant.ofEpochMilli(maiorInstante);
        }
        return leitura;
    }

    // Lê só a última partição de data do lote anterior (quando o lote cruza a meia-noite
    // são duas) e devolve a última linha, usada como base do 1º delta de rede.
    private LinhaCsv ultimaLinha(String bucketName, List<String> chaves) {
        String maiorData = null;

        for (String chave : chaves) {
            Matcher casamento = DATA_PARTICAO.matcher(chave);
            if (casamento.find()) {
                String data = casamento.group(1);
                if (maiorData == null || data.compareTo(maiorData) > 0) {
                    maiorData = data;
                }
            }
        }

        List<LinhaCsv> linhas = new ArrayList<>();
        for (String chave : chaves) {
            Matcher casamento = DATA_PARTICAO.matcher(chave);
            if (maiorData == null || (casamento.find() && maiorData.equals(casamento.group(1)))) {
                linhas.addAll(lerLinhas(bucketName, chave));
            }
        }
        if (linhas.isEmpty()) {
            return null;
        }
        Collections.sort(linhas, Comparator.comparingLong(linha -> linha.instante));
        return linhas.get(linhas.size() - 1);
    }

    // Parser manual: split(",", -1) + mapa índice←nome do cabeçalho, sem dependência nova.
    private List<LinhaCsv> lerLinhas(String bucketName, String chave) {
        List<LinhaCsv> linhas = new ArrayList<>();
        String conteudo = s3Service.lerArquivo(bucketName, chave);

        String[] partes = conteudo.split("\n", -1);
        if (partes.length == 0) {
            return linhas;
        }

        String[] cabecalho = partes[0].split(",", -1);
        Map<String, Integer> colunas = new HashMap<>();
        for (int i = 0; i < cabecalho.length; i++) {
            colunas.put(cabecalho[i].trim(), i);
        }

        for (int i = 1; i < partes.length; i++) {
            String texto = partes[i].trim();
            if (texto.isEmpty()) {
                continue;
            }

            String[] campos = texto.split(",", -1);

            // Os ids do próprio CSV precisam ser válidos quando existem. A identidade do
            // servidor vem da partição da chave, que já foi validada no regex.
            Integer idEmpresaCsv = inteiro(campos, colunas, "id_empresa");
            Integer idServidorCsv = inteiro(campos, colunas, "id_servidor");
            if ((idEmpresaCsv != null && idEmpresaCsv <= 0)
                    || (idServidorCsv != null && idServidorCsv <= 0)) {
                continue;
            }

            String dataHora = texto(campos, colunas, "data_hora");
            Long instante = IntegracaoJiraSlack.timestamp(dataHora);
            if (dataHora == null || instante == null) {
                continue;
            }

            LinhaCsv linha = new LinhaCsv();
            linha.dataHora = dataHora;
            linha.instante = instante;
            linha.username = texto(campos, colunas, "username");
            linha.cpu = numero(campos, colunas, "cpu");
            linha.ram = numero(campos, colunas, "ram");
            linha.disco = numero(campos, colunas, "disco");
            linha.bytesEnviados = numero(campos, colunas, "bytes_enviados");
            linha.bytesRecebidos = numero(campos, colunas, "bytes_recebidos");
            linhas.add(linha);
        }

        return linhas;
    }

    // Valor ausente, vazio ou não numérico tem que continuar ausente, não pode virar zero.
    private static Double numero(String[] campos, Map<String, Integer> colunas, String nome) {
        Integer i = colunas.get(nome);
        if (i == null || i >= campos.length) {
            return null;
        }
        String valor = campos[i].trim();
        if (valor.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(valor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer inteiro(String[] campos, Map<String, Integer> colunas, String nome) {
        Double valor = numero(campos, colunas, nome);
        return valor == null ? null : valor.intValue();
    }

    private static String texto(String[] campos, Map<String, Integer> colunas, String nome) {
        Integer i = colunas.get(nome);
        if (i == null || i >= campos.length) {
            return null;
        }
        String valor = campos[i].trim();
        return valor.isEmpty() ? null : valor;
    }

    // Bytes são contadores cumulativos: Mbps = delta de bytes / segundos reais * 8 / 1e6.
    // Reset do contador (delta negativo) vale zero.
    private static Double mbps(Double atual, Double anterior, long segundos) {
        if (atual == null || anterior == null) {
            return null;
        }
        double delta = atual - anterior;
        if (delta < 0) {
            delta = 0;
        }
        return delta / segundos * 8 / 1_000_000.0;
    }

    // O Z no lote é enganoso: o script Python gera o valor no fuso de Brasília.
    private static Instant instanteLote(String lote) {
        String semZ = lote.substring(0, lote.length() - 1);
        LocalDateTime local = LocalDateTime.parse(semZ, FORMATO_LOTE);
        return local.atZone(BRASILIA).toInstant();
    }

    // Resultado de uma leitura da silver, com o que a rodada precisa para decidir.
    public static class LeituraSilver {
        public final List<LeituraServidor> servidores = new ArrayList<>();
        public final Map<String, String> lotesProcessados = new HashMap<>();
        public Instant referencia = null;
    }

    private static class ChavesServidor {
        private final int idEmpresa;
        private final int idServidor;
        private final Map<String, List<String>> chavesPorLote = new HashMap<>();

        private ChavesServidor(int idEmpresa, int idServidor) {
            this.idEmpresa = idEmpresa;
            this.idServidor = idServidor;
        }

        private String chave() {
            return idEmpresa + "-" + idServidor;
        }
    }

    private static class LinhaCsv {
        private String dataHora;
        private long instante;
        private String username;
        private Double cpu;
        private Double ram;
        private Double disco;
        private Double bytesEnviados;
        private Double bytesRecebidos;
    }
}
