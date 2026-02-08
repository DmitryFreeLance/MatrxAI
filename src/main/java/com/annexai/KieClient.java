package com.annexai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

public class KieClient {
    private final Config config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public KieClient(Config config) {
        this.config = config;
        this.httpClient = new OkHttpClient();
    }

    public String uploadFileUrl(String fileUrl, String fileName) throws IOException {
        String payload = "{" +
                "\"fileUrl\":\"" + escape(fileUrl) + "\"," +
                "\"uploadPath\":\"telegram\"," +
                "\"fileName\":\"" + escape(fileName) + "\"" +
                "}";

        RequestBody body = RequestBody.create(payload, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.kieUploadBase + "/api/file-url-upload")
                .addHeader("Authorization", "Bearer " + config.kieApiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("Kie upload failed: " + response.code() + " " + err);
            }
            String respBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = mapper.readTree(respBody);
            String downloadUrl = json.path("data").path("downloadUrl").asText();
            return downloadUrl.isBlank() ? json.path("data").path("fileUrl").asText() : downloadUrl;
        }
    }

    public String createNanoBananaTask(String model, String prompt, List<String> imageUrls, String aspectRatio, String outputFormat, String resolution) throws IOException {
        String payload;
        if ("nano-banana-pro".equalsIgnoreCase(model)) {
            StringBuilder images = new StringBuilder("[");
            for (int i = 0; i < imageUrls.size(); i++) {
                if (i > 0) {
                    images.append(",");
                }
                images.append("\"").append(escape(imageUrls.get(i))).append("\"");
            }
            images.append("]");

            payload = "{" +
                    "\"model\":\"" + escape(model) + "\"," +
                    "\"input\":{"
                    + "\"prompt\":\"" + escape(prompt) + "\"," +
                    "\"image_input\":" + images + "," +
                    "\"aspect_ratio\":\"" + escape(aspectRatio) + "\"," +
                    "\"resolution\":\"" + escape(resolution) + "\"," +
                    "\"output_format\":\"" + escape(outputFormat) + "\"" +
                    "}" +
                    "}";
        } else if ("google/nano-banana-edit".equalsIgnoreCase(model)) {
            StringBuilder images = new StringBuilder("[");
            for (int i = 0; i < imageUrls.size(); i++) {
                if (i > 0) {
                    images.append(",");
                }
                images.append("\"").append(escape(imageUrls.get(i))).append("\"");
            }
            images.append("]");
            payload = "{" +
                    "\"model\":\"" + escape(model) + "\"," +
                    "\"input\":{"
                    + "\"prompt\":\"" + escape(prompt) + "\"," +
                    "\"image_urls\":" + images + "," +
                    "\"output_format\":\"" + escape(outputFormat) + "\"," +
                    "\"image_size\":\"" + escape(aspectRatio) + "\"" +
                    "}" +
                    "}";
        } else {
            payload = "{" +
                    "\"model\":\"" + escape(model) + "\"," +
                    "\"input\":{"
                    + "\"prompt\":\"" + escape(prompt) + "\"," +
                    "\"output_format\":\"" + escape(outputFormat) + "\"," +
                    "\"image_size\":\"" + escape(aspectRatio) + "\"" +
                    "}" +
                    "}";
        }

        RequestBody body = RequestBody.create(payload, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.kieApiBase + "/api/v1/jobs/createTask")
                .addHeader("Authorization", "Bearer " + config.kieApiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("Kie createTask failed: " + response.code() + " " + err);
            }
            String respBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = mapper.readTree(respBody);
            return json.path("data").path("taskId").asText();
        }
    }

    public TaskInfo getTaskInfo(String taskId) throws IOException {
        HttpUrl url = HttpUrl.parse(config.kieApiBase + "/api/v1/jobs/recordInfo")
                .newBuilder()
                .addQueryParameter("taskId", taskId)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.kieApiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("Kie recordInfo failed: " + response.code() + " " + err);
            }
            String respBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = mapper.readTree(respBody);
            JsonNode data = json.path("data");
            TaskInfo info = new TaskInfo();
            info.taskId = data.path("taskId").asText();
            info.state = data.path("state").asText();
            info.resultJson = data.path("resultJson").asText();
            String failReason = data.path("failReason").asText();
            if (failReason == null || failReason.isBlank()) {
                failReason = data.path("failMsg").asText();
            }
            info.failReason = failReason;
            return info;
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class TaskInfo {
        public String taskId;
        public String state;
        public String resultJson;
        public String failReason;
    }
}
