package school.sptech.dto;

// Representa o nível de prioridade atribuído ao ticket:
public class PriorityDto {

    private String name;

    public PriorityDto() {
    }

    public PriorityDto(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}