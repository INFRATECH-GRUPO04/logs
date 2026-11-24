package school.sptech;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

// S3Service -> aplica as operações relacionadas ao bucket

// S3Provider -> executa a comunicação com a nuvem da AWS.
public class S3Provider {
    public S3Client getS3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}