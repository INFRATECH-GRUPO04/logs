package school.sptech.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import school.sptech.dto.IssueFieldsDto;
import school.sptech.dto.IssueRequestDto;
import school.sptech.dto.IssueTypeDto;
import school.sptech.dto.ProjectDto;
import school.sptech.dto.PriorityDto;


// API REST v3 do Jira (Atlassian Document Format ou ADF):
// "Para fazer a integração com o Jira, utilizamos a API REST porque é através dela que
// conseguimos nos comunicar com o sistema de forma automatizada. É a API que recebe os
// dados estruturados da nossa aplicação Java e os transforma em tarefas reais dentro do Jira."
public class Jira {

    // final: não deixa mudar o valor da variável
    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public Jira(String baseUrl, String email, String apiToken) {
        this.baseUrl = baseUrl;

        String auth = email + ":" + apiToken;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    public String createIssue(
            String projectKey,
            String summary,
            String description,
            String issueType,
            String priority,
            List<String> labels
    ) throws IOException, InterruptedException {

        // Monta o corpo da requisição usando os DTOs
        ProjectDto project = new ProjectDto(projectKey);
        IssueTypeDto type = new IssueTypeDto(issueType);
        PriorityDto priorityDto = new PriorityDto(priority);

        IssueFieldsDto fields = new IssueFieldsDto(
                project,
                summary,
                type,
                description,
                priorityDto,
                labels
        );

        IssueRequestDto issueRequest = new IssueRequestDto(fields);

        // Transforma o objeto Java em JSON
        String json = objectMapper.writeValueAsString(issueRequest);

        // Cria a requisição POST para o endpoint de criação de issues
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/2/issue"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // Envia a requisição
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            return response.body();
        }

        throw new RuntimeException("Jira request failed: " + status + " - " + response.body());
    }

    // Serve para não criar dois chamados para a mesma alta: procura uma issue aberta
    // com as labels da empresa, do servidor e do componente.
    // A busca é fail-open: se der qualquer erro, devolve false e o chamado é criado mesmo assim.
    public boolean existeChamadoAberto(String projectKey, List<String> labels) {
        try {
            StringBuilder jql = new StringBuilder("project = \"").append(projectKey).append("\"");
            for (String label : labels) {
                jql.append(" AND labels = \"").append(label).append("\"");
            }
            jql.append(" AND statusCategory != Done");

            String url = baseUrl + "/rest/api/3/search/jql?jql="
                    + URLEncoder.encode(jql.toString(), StandardCharsets.UTF_8)
                    + "&maxResults=1&fields=key";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Jira search failed: " + status + " - " + response.body());
            }

            JsonNode issues = objectMapper.readTree(response.body()).path("issues");
            return issues.isArray() && !issues.isEmpty();
        } catch (Exception e) {
            System.err.println("Aviso: não foi possível consultar chamados abertos no Jira: "
                    + e.getMessage() + ". Vai criar o chamado mesmo assim.");
            return false;
        }
    }
}