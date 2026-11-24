package school.sptech.dto;

// DTO: representa como objeto Java o JSON { "fields": { ... } } enviado ao criar uma issue.
// Estrutura os dados enviados em requisições para a criação ou atualização de ticket:
public class IssueRequestDto {

    private IssueFieldsDto fields;

    public IssueRequestDto() {
    }

    public IssueRequestDto(IssueFieldsDto fields) {
        this.fields = fields;
    }

    public IssueFieldsDto getFields() {
        return fields;
    }

    public void setFields(IssueFieldsDto fields) {
        this.fields = fields;
    }
}
