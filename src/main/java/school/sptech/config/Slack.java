package school.sptech.config;

// Jackson: usado para transformar objetos Java em JSON
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Slack {

    // URL do webhook que aponta para o canal do Slack
    private final String webhookUrl;

    // Cliente responsável por realizar as requisições HTTP
    private final HttpClient httpClient;

    // Responsável por converter objetos Java para JSON
    private final ObjectMapper objectMapper;


    // Construtor da classe
    // Recebe a URL do webhook e prepara os objetos necessários para realizar as requisições
    public Slack(String webhookUrl) {
        this.webhookUrl = webhookUrl;

        this.httpClient = HttpClient.newHttpClient();

        this.objectMapper = new ObjectMapper();
    }


    // Métudo genérico para enviar uma mensagem simples para o Slack.
    // utilizamos porque é útil para mensagens comuns, sem uma estrutura
    // mais elaborada de "Blocks"
    public void sendMessage(String message)
            throws IOException, InterruptedException {

        // Cria o corpo da requisição.
        // O Slack espera receber um JSON contendo "text".
        Map<String, Object> body = new HashMap<>();
        body.put("text", message);

        // Converte o Map Java para uma String JSON.
        String json = objectMapper.writeValueAsString(body);

        // Envia o JSON para o Slack.
        sendRequest(json);
    }

    // Metodo específico para avisar que uma issue foi criada:
    // Recebe os dados da issue que acabou de ser criada no Jira.
    public void sendIssueCreatedMessage(
            String issueKey,
            String summary,
            String issueType,
            String issueUrl
    ) throws IOException, InterruptedException {


        // texto que aparecerá no cabeçalho da mensagem.
        Map<String, Object> headerText = Map.of(
                "type", "plain_text",
                "text", "🚨 Nova issue criada no Jira!"
        );

        // Define que esse conteúdo será um bloco do tipo "header"
        Map<String, Object> header = Map.of(
                "type", "header",
                "text", headerText
        );

        // Informações do ticket:

        // Monta o texto com os dados recebidos do Jira:
        Map<String, Object> sectionText = Map.of(
                "type", "mrkdwn",
                "text",
                "*Issue:* `" + issueKey + "`\n" +
                        "*Título:* " + summary + "\n" +
                        "*Tipo:* " + issueType
        );

        // Define que essas informações serão exibida dentro de um bloco do Slack do tipo "section"
        Map<String, Object> section = Map.of(
                "type", "section",
                "text", sectionText
        );

        // BOTÃO "abrir no jira":

        // Texto que aparecerá dentro do botão.
        Map<String, Object> buttonText = Map.of(
                "type", "plain_text",
                "text", "Abrir no Jira"
        );

        // Configura o botão:
        // A URL recebida será a URL específica da issue.
        Map<String, Object> button = Map.of(
                "type", "button",
                "text", buttonText,
                "url", issueUrl
        );

        // Cria um bloco de ações do Slack.
        // Dentro dele colocamos o botão criado antes
        Map<String, Object> actions = Map.of(
                "type", "actions",
                "elements", List.of(button)
        );


        //  cria linha divisória entre partes da mensagem:
        Map<String, Object> divider = Map.of(
                "type", "divider"
        );

        // Corpo completo da mensagem: Junta todos os blocos anteriores em uma única mensagem:
        Map<String, Object> body = Map.of(

                "text", "Nova issue criada no Jira: " + issueKey,

                // Lista de blocos que formarão a mensagem visual.
                "blocks", List.of(
                        header,
                        divider,
                        section,
                        actions
                )
        );

        // Converte toda a estrutura Java para JSON
        String json = objectMapper.writeValueAsString(body);

        // Envia o JSON para o Slack
        sendRequest(json);
    }

    // Envio da requisição http:

    // Os métodos anteriores só precisam montar a mensagem -> Aqui é onde ela realmente é enviada:
    private void sendRequest(String json)
            throws IOException, InterruptedException {

        // Monta a requisição HTTP
        HttpRequest request = HttpRequest.newBuilder()

                // Define para onde a requisição será enviada
                .uri(URI.create(webhookUrl))

                // Informa que estamos enviando JSON
                .header("Content-Type", "application/json")

                // Define que a requisição será POST
                .POST(HttpRequest.BodyPublishers.ofString(json))

                // Finaliza a construção da requisição
                .build();

        // Cria o cliente HTTP e envia a requisição
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );


        // Mostra no console o código retornado pelo Slack.(200 significa que a requisição foi aceita)
        System.out.println("Slack: " + response.statusCode());

        // Mostra a resposta do Slack
        System.out.println(response.body());

        // Se o Slack retornar algo diferente de 2xx = erro
        if (response.statusCode() < 200 ||
                response.statusCode() >= 300) {

            throw new RuntimeException(
                    "Slack request failed: "
                            + response.statusCode()
                            + " - "
                            + response.body()
            );
        }
    }
}