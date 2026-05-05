package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*") // Change to your Vercel URL before deploy
public class ExplainController {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    private final WebClient webClient = WebClient.create("https://generativelanguage.googleapis.com");
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/explain")
    public Mono<String> explainCode(@RequestBody Map<String, String> request) {
        String code = request.get("code");

        if (code == null || code.trim().isEmpty()) {
            return Mono.error(new RuntimeException("Code cannot be empty"));
        }

        String prompt = "Explain this code in simple English for a beginner. Use examples, be detailed, and use markdown for formatting:\n" + code;

        String escapedPrompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        String requestBody = """
                {
                  "contents": [{
                    "parts": [{"text": "%s"}]
                  }],
                  "generationConfig": {
                    "maxOutputTokens": 2048,
                    "temperature": 0.7
                  }
                }
                """.formatted(escapedPrompt);

        return callGemini(requestBody, "gemini-2.5-flash")
                .onErrorResume(e -> callGemini(requestBody, "gemini-2.0-flash"))
                .onErrorResume(e -> callGemini(requestBody, "gemini-2.5-flash-lite"))
                .onErrorReturn("Gemini is currently overloaded or your quota is exceeded. Please wait 1 minute and try again, or check billing at https://console.cloud.google.com/billing");
    }

    private Mono<String> callGemini(String requestBody, String model) {
        System.out.println("Trying model: " + model);
        return webClient.post()
                .uri("/v1beta/models/" + model + ":generateContent?key=" + geminiApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class).map(body -> {
                            System.out.println("GOOGLE ERROR for " + model + ": " + body);
                            return new RuntimeException("Google API Error: " + body);
                        })
                )
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        JsonNode root = mapper.readTree(json);
                        return root.path("candidates")
                                .get(0)
                                .path("content")
                                .path("parts")
                                .get(0)
                                .path("text")
                                .asText();
                    } catch (Exception e) {
                        System.out.println("Parse error: " + e.getMessage());
                        throw new RuntimeException("Error parsing Gemini response");
                    }
                });
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleError(RuntimeException ex) {
        return ex.getMessage();
    }
}