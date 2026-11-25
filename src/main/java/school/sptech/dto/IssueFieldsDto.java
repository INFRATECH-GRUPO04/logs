package school.sptech.dto;

import java.util.List;

// DTO: representa os campos enviados para a criação de uma Issue no Jira.
// Agrupa os campos do ticket:
public class IssueFieldsDto {

    private ProjectDto project;
    private String summary;
    private IssueTypeDto issuetype;
    private String description;
    private PriorityDto priority;
    private List<String> labels;

    public IssueFieldsDto() {
    }

    public IssueFieldsDto(
            ProjectDto project,
            String summary,
            IssueTypeDto issuetype,
            String description,
            PriorityDto priority,
            List<String> labels
    ) {
        this.project = project;
        this.summary = summary;
        this.issuetype = issuetype;
        this.description = description;
        this.priority = priority;
        this.labels = labels;
    }

    public ProjectDto getProject() {
        return project;
    }

    public void setProject(ProjectDto project) {
        this.project = project;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public IssueTypeDto getIssuetype() {
        return issuetype;
    }

    public void setIssuetype(IssueTypeDto issuetype) {
        this.issuetype = issuetype;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PriorityDto getPriority() {
        return priority;
    }

    public void setPriority(PriorityDto priority) {
        this.priority = priority;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }
}