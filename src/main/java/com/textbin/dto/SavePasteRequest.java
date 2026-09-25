package com.textbin.dto;

public class SavePasteRequest {
    private String content;
    private String title;
    private String syntaxLanguage;
    private String clientToken;

    public SavePasteRequest() {}

    public SavePasteRequest(String content, String title, String syntaxLanguage, String clientToken) {
        this.content = content;
        this.title = title;
        this.syntaxLanguage = syntaxLanguage;
        this.clientToken = clientToken;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSyntaxLanguage() {
        return syntaxLanguage;
    }

    public void setSyntaxLanguage(String syntaxLanguage) {
        this.syntaxLanguage = syntaxLanguage;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
