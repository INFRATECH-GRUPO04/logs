package school.sptech;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public class DatabaseConfig {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseConfig() {
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setUrl("jdbc:mysql://"
                + System.getenv().getOrDefault("DB_HOST", "nome-do-container-mysql")
                + ":3306/"
                + System.getenv().getOrDefault("DB_DATABASE", "infratech"));
        dataSource.setUsername(System.getenv().getOrDefault("DB_USER", "aluno"));
        dataSource.setPassword(System.getenv().getOrDefault("DB_PASSWORD", ""));

        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    //JdbcTemplate classe do Spring JDBC que simplifica a execução de operações no banco de dados:
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}