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

public class OpenAIProvider implements LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("LLMCommandMod");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    
    private final Config config;
    private String model;
    
    public OpenAIProvider(Config config) {
        this.config = config;
        this.model = config.getOpenaiModel();
    }
    
    @Override
    public String getProviderName() {
        return "openai";
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
        String apiKey = config.getOpenaiApiKey();
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("your_openai_key_here");
    }
    
    @Override
    public CompletableFuture<Void> streamResponse(String query, ServerCommandSource source) {
        return CompletableFuture.runAsync(() -> {
            try {
                log("Using model: " + model);
                
                // Set up OpenAI API request
                String apiUrl = "https://api.openai.com/v1/chat/completions";
                String apiKey = config.getOpenaiApiKey();
                
                String requestBodyJson;
                if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
                    log("Using system prompt: " + config.getSystemPrompt());
                    requestBodyJson = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true,\"max_tokens\":2000}",
                        model, 
                        config.getSystemPrompt().replace("\"", "\\\""),
                        query.replace("\"", "\\\"")
                    );
                } else {
                    requestBodyJson = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true,\"max_tokens\":2000}",
                        model, 
                        query.replace("\"", "\\\"")
                    );
                }
                
                log("Sending request to OpenAI API with query: " + query);
                if (config.getDebugMode()) {
                    log("Request body: " + requestBodyJson);
                    log("[LLMCommandMod] DEBUG: Sending to URL: " + apiUrl);
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
                
                // For keeping track of response chunks
                StringBuilder currentMessage = new StringBuilder();
                AtomicReference<String> lastMessageRef = new AtomicReference<>("");
                
                // Stream the response
                try {
                    log("Starting streaming response from OpenAI");
                    
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
                        
                        String errorMessage = "OpenAI API returned error code " + statusCode;
                        if (errorBody.length() > 0) {
                            errorMessage += ": " + errorBody.toString();
                            log("[LLMCommandMod] API Error: " + errorBody.toString());
                        }
                        throw new Exception(errorMessage);
                    }
                    
                    // Process successful response
                    response.body().transferTo(handleOpenAIStream(source, currentMessage, lastMessageRef));
                    log("OpenAI stream completed successfully");
                } catch (Exception e) {
                    logError("Error during OpenAI streaming", e);
                    MessageUtils.sendMessageToMinecraft(source, "§cError talking to OpenAI: " + e.getMessage() + "§r");
                    throw e;
                }
            } catch (Exception e) {
                logError("Error executing OpenAI request", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public CompletableFuture<String> getResponse(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Non-streaming request to OpenAI API
                String apiUrl = "https://api.openai.com/v1/chat/completions";
                String apiKey = config.getOpenaiApiKey();
                
                String requestBodyJson;
                if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
                    requestBodyJson = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"max_tokens\":2000}",
                        model, 
                        config.getSystemPrompt().replace("\"", "\\\""),
                        query.replace("\"", "\\\"")
                    );
                } else {
                    requestBodyJson = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"max_tokens\":2000}",
                        model, 
                        query.replace("\"", "\\\"")
                    );
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();
                
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                
                // Parse JSON response
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                
                // Extract result from OpenAI response
                return jsonResponse.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            } catch (Exception e) {
                logError("Error in non-streaming OpenAI request", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    private java.io.OutputStream handleOpenAIStream(
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
                    log("Received line from OpenAI: " + line);
                }
                
                // Handle OpenAI stream format
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    
                    // Skip [DONE] marker
                    if (data.equals("[DONE]")) {
                        if (currentMessage.length() > 0) {
                            MessageUtils.sendMessageToMinecraft(source, currentMessage.toString());
                            currentMessage.setLength(0);
                        }
                        return;
                    }
                    
                    try {
                        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                        
                        // Extract content from choices
                        if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                            JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                            
                            // Check for content in delta
                            if (choice.has("delta") && choice.getAsJsonObject("delta").has("content")) {
                                String textChunk = choice.getAsJsonObject("delta").get("content").getAsString();
                                currentMessage.append(textChunk);
                                
                                // Check if we need to send a message because we're approaching the length limit
                                if (currentMessage.length() >= MessageUtils.getMaxMessageLength()) {
                                    // Find a good break point
                                    int breakPoint = MessageUtils.findBreakPoint(currentMessage.toString(), MessageUtils.getMaxMessageLength());
                                    
                                    // Extract the part to send
                                    String toSend = currentMessage.substring(0, breakPoint);
                                    
                                    // Only send if different from last message sent
                                    if (!toSend.equals(lastMessageRef.get())) {
                                        MessageUtils.sendMessageToMinecraft(source, toSend);
                                        lastMessageRef.set(toSend);
                                    }
                                    
                                    // Keep the remainder for the next message
                                    currentMessage.delete(0, breakPoint);
                                }
                            }
                            
                            // Check for finish_reason to send remaining text
                            if (choice.has("finish_reason") && choice.get("finish_reason").getAsString() != null) {
                                if (currentMessage.length() > 0) {
                                    MessageUtils.sendMessageToMinecraft(source, currentMessage.toString());
                                    currentMessage.setLength(0);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logError("Error parsing OpenAI JSON", e);
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