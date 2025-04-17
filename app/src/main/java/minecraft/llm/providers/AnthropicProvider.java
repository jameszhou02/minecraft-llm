package minecraft.llm.providers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import minecraft.llm.config.Config;
import minecraft.llm.util.MessageUtils;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class AnthropicProvider implements LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("LLMCommandMod");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    
    private final Config config;
    private String model;
    
    public AnthropicProvider(Config config) {
        this.config = config;
        this.model = config.getAnthropicModel();
    }
    
    @Override
    public String getProviderName() {
        return "anthropic";
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
        String apiKey = config.getAnthropicApiKey();
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("your_anthropic_key_here");
    }
    
    @Override
    public CompletableFuture<Void> streamResponse(String query, ServerCommandSource source) {
        return CompletableFuture.runAsync(() -> {
            try {
                log("Using model: " + model);
                
                // Set up Anthropic API request
                String apiUrl = "https://api.anthropic.com/v1/messages";
                String apiKey = config.getAnthropicApiKey();
                
                String requestBodyJson;
                if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
                    log("Using system prompt: " + config.getSystemPrompt());
                    requestBodyJson = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"system\":\"%s\",\"stream\":true,\"max_tokens\":2000}",
                        model, 
                        query.replace("\"", "\\\""),
                        config.getSystemPrompt().replace("\"", "\\\"")
                    );
                } else {
                    requestBodyJson = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true,\"max_tokens\":2000}",
                        model, 
                        query.replace("\"", "\\\"")
                    );
                }
                
                log("Sending request to Anthropic API with query: " + query);
                if (config.getDebugMode()) {
                    log("Request body: " + requestBodyJson);
                    // Print directly to console for visibility
                    log("[LLMCommandMod] DEBUG: Sending to URL: " + apiUrl);
                    log("[LLMCommandMod] DEBUG: With headers: content-type: application/json, x-api-key: [API_KEY_HIDDEN], anthropic-version: 2023-06-01");
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
                
                // For keeping track of response chunks
                StringBuilder currentMessage = new StringBuilder();
                AtomicReference<String> lastMessageRef = new AtomicReference<>("");
                
                // Stream the response
                try {
                    log("Starting streaming response");
                    
                    // Use a timeout for the request
                    HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    
                    // Check response status
                    int statusCode = response.statusCode();
                    if (config.getDebugMode()) {
                        log("[LLMCommandMod] DEBUG: Received response with status code: " + statusCode);
                    }
                    
                    if (statusCode != 200) {
                        // Try to read error message
                        StringBuilder errorBody = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                errorBody.append(line);
                            }
                        }
                        
                        String errorMessage = "API returned error code " + statusCode;
                        if (errorBody.length() > 0) {
                            errorMessage += ": " + errorBody.toString();
                            log("[LLMCommandMod] API Error: " + errorBody.toString());
                            // Log the request payload that caused the error
                            log("[LLMCommandMod] API Request: " + requestBodyJson);
                        }
                        throw new Exception(errorMessage);
                    }
                    
                    // Process successful response
                    response.body().transferTo(handleAnthropicStream(source, currentMessage, lastMessageRef));
                    log("Stream completed successfully");
                } catch (Exception e) {
                    logError("Error during streaming", e);
                    MessageUtils.sendMessageToMinecraft(source, "§cError talking to Claude: " + e.getMessage() + "§r");
                    throw e;
                }
            } catch (Exception e) {
                logError("Error executing Anthropic request", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public CompletableFuture<String> getResponse(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Fallback non-streaming method
                String apiUrl = "https://api.anthropic.com/v1/messages";
                String apiKey = config.getAnthropicApiKey();
                String requestBody = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"max_tokens\":2000}",
                    model, 
                    query.replace("\"", "\\\"")
                );
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                
                // Parse JSON response with Gson
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                
                // Extract result from Anthropic response
                return jsonResponse.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
            } catch (Exception e) {
                logError("Error in non-streaming Anthropic request", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    private java.io.OutputStream handleAnthropicStream(
        ServerCommandSource source,
        StringBuilder currentMessage,
        AtomicReference<String> lastMessageRef
    ) {
        return new java.io.OutputStream() {
            private final StringBuilder lineBuffer = new StringBuilder();
            
            @Override
            public void write(int b) {
                char c = (char) b;
                lineBuffer.append(c);
                
                // Process complete lines
                if (c == '\n') {
                    processLine(lineBuffer.toString(), source, currentMessage, lastMessageRef);
                    lineBuffer.setLength(0);
                }
            }
            
            private void processLine(String line, ServerCommandSource source, StringBuilder currentMessage, AtomicReference<String> lastMessageRef) {
                // Skip empty lines and event prefixes
                if (line.isEmpty() || line.equals("\n")) {
                    return;
                }
                
                // Log raw line if in debug mode
                if (config.getDebugMode()) {
                    log("Received line: " + line);
                }
                
                // Handle the new stream format from Anthropic
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    
                    try {
                        // Skip ping events and empty data
                        if (data.isEmpty() || data.equals("\"type\": \"ping\"}")) {
                            return;
                        }
                        
                        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                        
                        // Handle content_block_delta events which contain text chunks
                        if (json.has("type") && "content_block_delta".equals(json.get("type").getAsString())) {
                            if (json.has("delta") && json.getAsJsonObject("delta").has("text")) {
                                String textChunk = json.getAsJsonObject("delta").get("text").getAsString();
                                currentMessage.append(textChunk);
                                
                                // Check if we need to send a message because we're approaching the length limit
                                if (currentMessage.length() >= MessageUtils.getMaxMessageLength()) {
                                    // Find a good break point (space, period, etc.)
                                    int breakPoint = MessageUtils.findBreakPoint(currentMessage.toString(), MessageUtils.getMaxMessageLength());
                                    
                                    // Extract the part to send
                                    String toSend = currentMessage.substring(0, breakPoint);
                                    
                                    // Only send if different from last message sent
                                    // This avoids duplicate messages if chunks arrive mid-character
                                    if (!toSend.equals(lastMessageRef.get())) {
                                        MessageUtils.sendMessageToMinecraft(source, toSend);
                                        lastMessageRef.set(toSend);
                                    }
                                    
                                    // Keep the remainder for the next message
                                    currentMessage.delete(0, breakPoint);
                                }
                            }
                        }
                        // Handle message_stop event to send any remaining text
                        else if (json.has("type") && "message_stop".equals(json.get("type").getAsString())) {
                            if (currentMessage.length() > 0) {
                                MessageUtils.sendMessageToMinecraft(source, currentMessage.toString());
                                currentMessage.setLength(0);
                            }
                        }
                        // Handle content_block_stop event to display message if we have a complete block
                        else if (json.has("type") && "content_block_stop".equals(json.get("type").getAsString())) {
                            if (currentMessage.length() > 0 && currentMessage.length() < MessageUtils.getMaxMessageLength()) {
                                MessageUtils.sendMessageToMinecraft(source, currentMessage.toString());
                                currentMessage.setLength(0);
                            }
                        }
                    } catch (Exception e) {
                        logError("Error parsing JSON", e);
                    }
                }
            }
        };
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