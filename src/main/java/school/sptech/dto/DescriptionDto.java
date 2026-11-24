package school.sptech.dto;

import java.util.List;

// Representa a descrição detalhada ou o conteúdo descritivo do ticket:
// Sendo dividida em arvore, tendo o "documento", depois o "paragrafo" e depois o "texto".
public class DescriptionDto {

    private String type;
    private int version;
    private List<ContentDto> content;

    public DescriptionDto() {
    }

    public DescriptionDto(String text) {
        this.type = "doc";
        this.version = 1;
        this.content = List.of(
                new ContentDto(text)
        );
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<ContentDto> getContent() {
        return content;
    }

    public void setContent(List<ContentDto> content) {
        this.content = content;
    }

    public static class ContentDto {

        private String type;
        private List<TextDto> content;

        public ContentDto() {
        }

        // paragrafo da descrição:
        public ContentDto(String text) {
            this.type = "paragraph";
            this.content = List.of(
                    new TextDto(text)
            );
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<TextDto> getContent() {
            return content;
        }

        public void setContent(List<TextDto> content) {
            this.content = content;
        }
    }

    public static class TextDto {

        private String type;
        private String text;

        public TextDto() {
        }

        // texto do paragrafo:
        public TextDto(String text) {
            this.type = "text";
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}