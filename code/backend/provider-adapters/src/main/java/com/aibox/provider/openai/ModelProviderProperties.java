package com.aibox.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "yuanzuo.model")
public class ModelProviderProperties {

    private Map<String, Provider> providers = new LinkedHashMap<>();

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    public static class Provider {
        private String protocol;
        private String baseUrl;
        private String apiKey;
        private String chatPath = "/v1/chat/completions";
        private String audioPath = "/v1/audio/transcriptions";
        private String imagePath = "/v1/images/generations";
        private String imageEditPath = "/v1/images/edits";
        private String speechPath = "/v1/audio/speech";
        private String videoPath = "/v1/videos/generations";
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getChatPath() { return chatPath; }
        public void setChatPath(String chatPath) { this.chatPath = chatPath; }
        public String getAudioPath() { return audioPath; }
        public void setAudioPath(String audioPath) { this.audioPath = audioPath; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
        public String getImageEditPath() { return imageEditPath; }
        public void setImageEditPath(String imageEditPath) { this.imageEditPath = imageEditPath; }
        public String getSpeechPath() { return speechPath; }
        public void setSpeechPath(String speechPath) { this.speechPath = speechPath; }
        public String getVideoPath() { return videoPath; }
        public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }
    }
}
