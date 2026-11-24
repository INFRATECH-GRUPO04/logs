package school.sptech;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public class DatabaseConfig {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseConfig() {
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setUrl("jdbc:mysql://nome-do-container-mysql:3306/infratech");
        dataSource.setUsername("aluno");
        dataSource.setPassword("A");

        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    //JdbcTemplate classe do Spring JDBC que simplifica a execução de operações no banco de dados:
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}