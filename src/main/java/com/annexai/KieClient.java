package com.annexai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class KieClient {
    private final Config config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public KieClient(Config config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(200, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
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
            String taskId = json.path("data").path("taskId").asText();
            if (taskId == null || taskId.isBlank()) {
                throw new IOException("Kie createTask returned empty taskId: " + respBody);
            }
            return taskId;
        }
    }

    public String createFluxTask(String model, String prompt, List<String> imageUrls, String aspectRatio, String resolution) throws IOException {
        StringBuilder images = new StringBuilder();
        if (imageUrls != null && !imageUrls.isEmpty()) {
            images.append("[");
            for (int i = 0; i < imageUrls.size(); i++) {
                if (i > 0) {
                    images.append(",");
                }
                images.append("\"").append(escape(imageUrls.get(i))).append("\"");
            }
            images.append("]");
        }

        List<String> fields = new java.util.ArrayList<>();
        fields.add("\"prompt\":\"" + escape(prompt) + "\"");
        if (images.length() > 0) {
            fields.add("\"input_urls\":" + images);
        }
        if (aspectRatio != null && !aspectRatio.isBlank()) {
            fields.add("\"aspect_ratio\":\"" + escape(aspectRatio) + "\"");
        }
        if (resolution != null && !resolution.isBlank()) {
            fields.add("\"resolution\":\"" + escape(resolution) + "\"");
        }
        String input = String.join(",", fields);
        String payload = "{" +
                "\"model\":\"" + escape(model) + "\"," +
                "\"input\":{" + input + "}" +
                "}";

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
            String taskId = json.path("data").path("taskId").asText();
            if (taskId == null || taskId.isBlank()) {
                throw new IOException("Kie createTask returned empty taskId: " + respBody);
            }
            return taskId;
        }
    }

    public String createIdeogramTask(String model,
                                     String prompt,
                                     String renderingSpeed,
                                     String style,
                                     Boolean expandPrompt,
                                     String imageSize,
                                     List<String> referenceImageUrls,
                                     String imageUrl,
                                     String maskUrl,
                                     Integer numImages,
                                     Double strength) throws IOException {
        List<String> fields = new java.util.ArrayList<>();
        fields.add("\"prompt\":\"" + escape(prompt) + "\"");
        if (renderingSpeed != null && !renderingSpeed.isBlank()) {
            fields.add("\"rendering_speed\":\"" + escape(renderingSpeed) + "\"");
        }
        if (style != null && !style.isBlank()) {
            fields.add("\"style\":\"" + escape(style) + "\"");
        }
        if (expandPrompt != null) {
            fields.add("\"expand_prompt\":" + (expandPrompt ? "true" : "false"));
        }
        if (imageSize != null && !imageSize.isBlank()) {
            fields.add("\"image_size\":\"" + escape(imageSize) + "\"");
        }
        if (numImages != null) {
            fields.add("\"num_images\":\"" + numImages + "\"");
        }
        if (strength != null) {
            fields.add("\"strength\":" + strength);
        }
        if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
            StringBuilder refs = new StringBuilder("[");
            for (int i = 0; i < referenceImageUrls.size(); i++) {
                if (i > 0) {
                    refs.append(",");
                }
                refs.append("\"").append(escape(referenceImageUrls.get(i))).append("\"");
            }
            refs.append("]");
            fields.add("\"reference_image_urls\":" + refs);
        }
        if (imageUrl != null && !imageUrl.isBlank()) {
            fields.add("\"image_url\":\"" + escape(imageUrl) + "\"");
        }
        if (maskUrl != null && !maskUrl.isBlank()) {
            fields.add("\"mask_url\":\"" + escape(maskUrl) + "\"");
        }

        String input = String.join(",", fields);
        String payload = "{" +
                "\"model\":\"" + escape(model) + "\"," +
                "\"input\":{" + input + "}" +
                "}";

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
            String taskId = json.path("data").path("taskId").asText();
            if (taskId == null || taskId.isBlank()) {
                throw new IOException("Kie createTask returned empty taskId: " + respBody);
            }
            return taskId;
        }
    }

    public String createGeminiTask(String model, String prompt, List<String> imageUrls, List<String> fileUrls) throws IOException {
        StringBuilder content = new StringBuilder(prompt == null ? "" : prompt.trim());
        if (imageUrls != null && !imageUrls.isEmpty()) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append("Изображения:\n");
            for (String url : imageUrls) {
                content.append(url).append("\n");
            }
        }
        if (fileUrls != null && !fileUrls.isEmpty()) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append("Файлы:\n");
            for (String url : fileUrls) {
                content.append(url).append("\n");
            }
        }
        List<ChatMessage> messages = List.of(new ChatMessage("user", content.toString().trim(), imageUrls));
        return createGeminiCompletion(model, messages);
    }

    public String createGeminiCompletion(String model, List<ChatMessage> messages) throws IOException {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Gemini messages are empty.");
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model == null ? "" : model);
        root.put("stream", false);
        ArrayNode arr = root.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode m = arr.addObject();
            m.put("role", msg.role == null ? "user" : msg.role);
            String content = msg.content == null ? "" : msg.content;
            if (msg.imageUrls != null && !msg.imageUrls.isEmpty()) {
                ArrayNode parts = m.putArray("content");
                ObjectNode textPart = parts.addObject();
                textPart.put("type", "text");
                textPart.put("text", content);
                for (String url : msg.imageUrls) {
                    if (url == null || url.isBlank()) {
                        continue;
                    }
                    ObjectNode imgPart = parts.addObject();
                    imgPart.put("type", "image_url");
                    ObjectNode img = imgPart.putObject("image_url");
                    img.put("url", url);
                }
            } else {
                m.put("content", content);
            }
        }

        String payload = mapper.writeValueAsString(root);
        RequestBody body = RequestBody.create(payload, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(geminiEndpointForModel(model))
                .addHeader("Authorization", "Bearer " + config.kieApiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("Kie gemini completion failed: " + response.code() + " " + err);
            }
            String respBody = response.body() != null ? response.body().string() : "{}";
            JsonNode json = mapper.readTree(respBody);
            JsonNode choices = json.path("choices");
            String content = "";
            if (choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).path("message");
                content = msg.path("content").asText();
            }
            if (content == null || content.isBlank()) {
                throw new IOException("Kie gemini completion returned empty content: " + respBody);
            }
            return content;
        }
    }

    private String geminiEndpointForModel(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("pro")) {
            return config.kieApiBase + "/gemini-3-pro/v1/chat/completions";
        }
        return config.kieApiBase + "/gemini-3-flash/v1/chat/completions";
    }

    public static class ChatMessage {
        public final String role;
        public final String content;
        public final List<String> imageUrls;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.imageUrls = List.of();
        }

        public ChatMessage(String role, String content, List<String> imageUrls) {
            this.role = role;
            this.content = content;
            this.imageUrls = imageUrls == null ? List.of() : imageUrls;
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
