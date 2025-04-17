package minecraft.llm.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import minecraft.llm.config.Config;
import minecraft.llm.util.MessageUtils;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class GeminiProvider implements LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("LLMCommandMod");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    
    private final Config config;
    private String model;
    
    public GeminiProvider(Config config) {
        this.config = config;
        this.model = config.getGeminiModel();
    }
    
    @Override
    public String getProviderName() {
        return "gemini";
    }
    
    @Override
    public String getCurrentModel() {
        return model;
    }
    
    @Override
    public void setModel(String model) {
        this.model = model;
    }
    
    @Override
    public boolean hasValidApiKey() {
        String apiKey = config.getGeminiApiKey();
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("your_gemini_key_here");
    }
    
    @Override
    public CompletableFuture<Void> streamResponse(String query, ServerCommandSource source) {
        return CompletableFuture.runAsync(() -> {
            try {
                log("Using model: " + model);
                
                // Set up Gemini API request
                String apiKey = config.getGeminiApiKey();
                // Note: Using streamGenerateContent even though we read the whole response now.
                // The non-streaming endpoint has a different structure.
                String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?key=" + apiKey;
                
                // Create request body
                String requestBodyJson;
                if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
                    log("Using system prompt with Gemini: " + config.getSystemPrompt());
                    requestBodyJson = String.format(
                        "{\"system_instruction\":{\"parts\":[{\"text\":\"%s\"}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"responseMimeType\":\"text/plain\"}}",
                        config.getSystemPrompt().replace("\"", "\\\""),
                        query.replace("\"", "\\\"")
                    );
                } else {
                    requestBodyJson = String.format(
                        "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"responseMimeType\":\"text/plain\"}}",
                        query.replace("\"", "\\\"")
                    );
                }
                
                log("Sending request to Gemini API with query: " + query);
                if (config.getDebugMode()) {
                    log("Request body: " + requestBodyJson);
                    log("[LLMCommandMod] DEBUG: Sending to URL: " + apiUrl);
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
                
                // For keeping track of response chunks
                StringBuilder fullMessage = new StringBuilder();
                
                // Read the full response instead of streaming chunk by chunk
                try {
                    log("Sending request and waiting for full response from Gemini...");
                    
                    // Use BodyHandlers.ofString() to get the full response body
                    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    
                    // Check response status
                    int statusCode = response.statusCode();
                    String responseBody = response.body();
                    
                    if (config.getDebugMode()) {
                        log("[LLMCommandMod] DEBUG: Received response with status code: " + statusCode);
                        log("[LLMCommandMod] DEBUG: Full response body: " + responseBody);
                    }
                    
                    if (statusCode != 200) {
                        String errorMessage = "Gemini API returned error code " + statusCode;
                        if (responseBody != null && !responseBody.isEmpty()) {
                            errorMessage += ": " + responseBody;
                            log("[LLMCommandMod] API Error Body: " + responseBody);
                        }
                        throw new Exception(errorMessage);
                    }
                    
                    // Process the full response body
                    // Gemini stream responses are often newline-separated JSON objects.
                    // We need to handle the potential array structure or sequence of objects.
                    try {
                        // Check if it's a JSON array
                        if (responseBody.trim().startsWith("[")) {
                            JsonArray jsonArray = JsonParser.parseString(responseBody).getAsJsonArray();
                            for (JsonElement element : jsonArray) {
                                extractTextFromGeminiCandidate(element.getAsJsonObject(), fullMessage);
                            }
                        } else {
                            // Assume newline-separated JSON objects
                            String[] jsonObjects = responseBody.split("\r?\n");
                            for (String jsonObjStr : jsonObjects) {
                                if (jsonObjStr.trim().isEmpty() || !jsonObjStr.trim().startsWith("{")) {
                                    continue; // Skip empty lines or non-JSON parts
                                }
                                try {
                                    JsonObject json = JsonParser.parseString(jsonObjStr).getAsJsonObject();
                                    extractTextFromGeminiCandidate(json, fullMessage);
                                } catch (Exception parseEx) {
                                    if (config.getDebugMode()) {
                                        log("Skipping invalid JSON object in stream: " + jsonObjStr + " | Error: " + parseEx.getMessage());
                                    }
                                }
                            }
                        }
                        
                    } catch (Exception e) {
                        logError("Error parsing full Gemini response", e);
                        log("Problematic response body: " + responseBody);
                        // Fallback: try treating the whole body as plain text if parsing fails completely?
                        // Or just report the parsing error. Let's report for now.
                        throw new Exception("Failed to parse Gemini response: " + e.getMessage());
                    }
                    
                    // Send the accumulated message in chunks
                    if (fullMessage.length() > 0) {
                        String finalMessage = fullMessage.toString();
                        int start = 0;
                        while (start < finalMessage.length()) {
                            int end = MessageUtils.findBreakPoint(finalMessage.substring(start), MessageUtils.getMaxMessageLength());
                            MessageUtils.sendMessageToMinecraft(source, finalMessage.substring(start, start + end));
                            start += end;
                        }
                    } else {
                        // Send a message indicating no content was received if applicable
                        MessageUtils.sendMessageToMinecraft(source, "§7(Received empty response from Gemini)§r");
                    }
                    
                    log("Gemini response processed successfully.");
                    
                } catch (Exception e) {
                    logError("Error during Gemini request/processing", e);
                    MessageUtils.sendMessageToMinecraft(source, "§cError talking to Gemini: " + e.getMessage() + "§r");
                    throw e; // Re-throw to be caught by the outer handler
                }
            } catch (Exception e) {
                logError("Error executing Gemini request", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public CompletableFuture<String> getResponse(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Non-streaming Gemini API request
                String apiKey = config.getGeminiApiKey();
                String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
                
                // Create request body
                String requestBodyJson;
                if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
                    requestBodyJson = String.format(
                        "{\"system_instruction\":{\"parts\":[{\"text\":\"%s\"}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}]}",
                        config.getSystemPrompt().replace("\"", "\\\""),
                        query.replace("\"", "\\\"")
                    );
                } else {
                    requestBodyJson = String.format(
                        "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}]}",
                        query.replace("\"", "\\\"")
                    );
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
                
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                
                // Process response
                if (response.statusCode() != 200) {
                    throw new Exception("Gemini API error: " + response.body());
                }
                
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                StringBuilder resultBuilder = new StringBuilder();
                
                // Extract text from the response
                if (jsonResponse.has("candidates") && jsonResponse.getAsJsonArray("candidates").size() > 0) {
                    JsonObject candidate = jsonResponse.getAsJsonArray("candidates").get(0).getAsJsonObject();
                    
                    if (candidate.has("content") && candidate.getAsJsonObject("content").has("parts")) {
                        JsonArray parts = candidate.getAsJsonObject("content").getAsJsonArray("parts");
                        
                        for (JsonElement part : parts) {
                            if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                                resultBuilder.append(part.getAsJsonObject().get("text").getAsString());
                            }
                        }
                    }
                }
                
                return resultBuilder.toString();
            } catch (Exception e) {
                logError("Error in non-streaming Gemini request", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    // Helper method to extract text from a single Gemini JSON object (candidate structure)
    private void extractTextFromGeminiCandidate(JsonObject json, StringBuilder messageBuilder) {
        if (json.has("candidates") && json.getAsJsonArray("candidates").size() > 0) {
            JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
            
            if (candidate.has("content") && candidate.getAsJsonObject("content").has("parts") && 
                candidate.getAsJsonObject("content").getAsJsonArray("parts").size() > 0) {
                
                JsonElement part = candidate.getAsJsonObject("content").getAsJsonArray("parts").get(0);
                
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    String textChunk = part.getAsJsonObject().get("text").getAsString();
                    messageBuilder.append(textChunk); 
                }
            }
        }
    }
    
    private void log(String message) {
        if (config.getDebugMode()) {
            LOGGER.info(message);
        }
    }
    
    private void logError(String message, Throwable error) {
        // Always log errors, even if debug mode is off
        if (error != null) {
            LOGGER.error(message, error);
        } else {
            LOGGER.error(message);
        }
    }
} 