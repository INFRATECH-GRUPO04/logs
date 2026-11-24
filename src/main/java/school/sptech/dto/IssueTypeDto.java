package school.sptech.dto;

// DTO: representa como objeto Java o JSON { "name": "..." } do tipo da issue (Task, Bug, Story...)
// Define o tipo da issue (como bug, tarefa ou história) no contexto do Jira:
public class IssueTypeDto {

    private String name;

    public IssueTypeDto() {
    }

    public IssueTypeDto(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
