package school.sptech;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import java.util.List;

// S3Service -> aplica as operações relacionadas ao bucket

// S3Provider -> executa a comunicação com a nuvem da AWS.

public class S3Service {
    private final S3Client s3Client;

    public S3Service(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    // Lista TODAS as chaves do prefixo, página por página. Sem paginação a lista fica
    // incompleta quando o data lake passa de mil arquivos.
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

    public String lerArquivo(String bucketName, String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key) //caminho do arquivo no S3
                .build();

        // baixa os dados do arquivo:
        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            StringBuilder conteudo = new StringBuilder();
            String linha;
            while ((linha = reader.readLine()) != null) {
                conteudo.append(linha).append("\n");
            }
            return conteudo.toString();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao ler dados do S3: " + e.getMessage(), e);
        }
    }
}
