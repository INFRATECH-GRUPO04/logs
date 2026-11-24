package school.sptech.dto;

// DTO: representa como objeto Java o JSON { "key": "..." } do projeto do Jira
// Contém as informações do projeto do Jira ao qual as issues estão vinculadas:
public class ProjectDto {

    private String key;

    public ProjectDto() {
    }

    public ProjectDto(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
