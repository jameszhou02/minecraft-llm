package minecraft.llm;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicReference;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class LLMCommandMod implements ModInitializer {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final int MAX_MESSAGE_LENGTH = 250; // Minecraft's limit is around 256, using 250 to be safe
    private static final Logger LOGGER = LoggerFactory.getLogger("LLMCommandMod");
    private Config config;
    
    @Override
    public void onInitialize() {
        // Load or create config
        this.config = loadConfig();
        registerLLMCommand();
        registerConfigCommand();
        log("LLM Command Mod initialized!");
        log("Using model: " + config.anthropicModel);
        log("Current provider: " + config.currentProvider);
        if (config.debugMode) {
            log("Debug mode is enabled - detailed logs will be written to .minecraft/logs/latest.log");
        }
    }
    
    private void log(String message) {
        if (config != null && config.debugMode) {
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
    
    private void registerLLMCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("llm")
                    .then(argument("query", StringArgumentType.greedyString())
                    .executes(this::executeLLMCommand))
            );
        });
    }
    
    private int executeLLMCommand(CommandContext<ServerCommandSource> context) {
        String query = StringArgumentType.getString(context, "query");
        ServerCommandSource source = context.getSource();
        
        source.sendFeedback(() -> Text.literal("§7Thinking...§r"), false);
        
        // Execute asynchronously to not block the main game thread
        CompletableFuture.runAsync(() -> {
            try {
                if (config.debugMode) {
                    // Print info directly to server console for visibility
                    log("[LLMCommandMod] DEBUG: Starting request to " + config.currentProvider + " with query: " + query);
                }
                
                // Call the appropriate API based on the current provider
                switch (config.currentProvider.toLowerCase()) {
                    case "anthropic":
                        // Check if API key is set
                        if (config.anthropicApiKey == null || config.anthropicApiKey.isEmpty() || 
                            config.anthropicApiKey.equals("your_anthropic_key_here")) {
                            source.sendFeedback(() -> Text.literal("§cError: Anthropic API key not set. Please set your Anthropic API key in config/llmcommand.json or use /llmconfig§r"), false);
                            return;
                        }
                        log("Using model: " + config.anthropicModel);
                        streamFromAnthropicLLM(query, source);
                        break;
                    case "openai":
                        // Check if API key is set
                        if (config.openaiApiKey == null || config.openaiApiKey.isEmpty() || 
                            config.openaiApiKey.equals("your_openai_key_here")) {
                            source.sendFeedback(() -> Text.literal("§cError: OpenAI API key not set. Please set your OpenAI API key in config/llmcommand.json or use /llmconfig§r"), false);
                            return;
                        }
                        log("Using model: " + config.openaiModel);
                        streamFromOpenAILLM(query, source);
                        break;
                    case "gemini":
                        // Check if API key is set
                        if (config.geminiApiKey == null || config.geminiApiKey.isEmpty() || 
                            config.geminiApiKey.equals("your_gemini_key_here")) {
                            source.sendFeedback(() -> Text.literal("§cError: Gemini API key not set. Please set your Gemini API key in config/llmcommand.json or use /llmconfig§r"), false);
                            return;
                        }
                        log("Using model: " + config.geminiModel);
                        streamFromGeminiLLM(query, source);
                        break;
                    default:
                        source.sendFeedback(() -> Text.literal("§cError: Invalid provider selected. Please use /llmconfig currentProvider to set a valid provider.§r"), false);
                }
            } catch (Exception e) {
                // Log the error
                logError("Error executing LLM command", e);
                
                // Send a more detailed error message to the player
                String errorMsg = "§cError: " + e.getMessage() + "§r";
                source.sendFeedback(() -> Text.literal(errorMsg), false);
                
                // Print stack trace for visibility
                log("[LLMCommandMod] ERROR when executing command:");
                e.printStackTrace();
            }
        });
        
        return Command.SINGLE_SUCCESS;
    }
    
    private void streamFromAnthropicLLM(String query, ServerCommandSource source) throws Exception {
        // Set up Anthropic API request
        String apiUrl = "https://api.anthropic.com/v1/messages";
        String apiKey = config.anthropicApiKey;
        
        String requestBodyJson;
        if (config.systemPrompt != null && !config.systemPrompt.isEmpty()) {
            log("Using system prompt: " + config.systemPrompt);
            requestBodyJson = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"system\":\"%s\",\"stream\":true,\"max_tokens\":2000}",
                config.anthropicModel, 
                query.replace("\"", "\\\""),
                config.systemPrompt.replace("\"", "\\\"")
            );
        } else {
            requestBodyJson = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true,\"max_tokens\":2000}",
                config.anthropicModel, 
                query.replace("\"", "\\\"")
            );
        }
        
        log("Sending request to Anthropic API with query: " + query);
        if (config.debugMode) {
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
            if (config.debugMode) {
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
                log("[LLMCommandMod] API Error: " + errorBody.toString());
                throw new Exception(errorMessage);
            }
            
            // Process successful response
            response.body().transferTo(handleAnthropicStream(source, currentMessage, lastMessageRef));
            log("Stream completed successfully");
        } catch (Exception e) {
            logError("Error during streaming", e);
            source.sendFeedback(() -> Text.literal("§cError talking to Claude: " + e.getMessage() + "§r"), false);
            throw e;
        }
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
                if (config.debugMode) {
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
                                if (currentMessage.length() >= MAX_MESSAGE_LENGTH) {
                                    // Find a good break point (space, period, etc.)
                                    int breakPoint = findBreakPoint(currentMessage.toString(), MAX_MESSAGE_LENGTH);
                                    
                                    // Extract the part to send
                                    String toSend = currentMessage.substring(0, breakPoint);
                                    
                                    // Only send if different from last message sent
                                    // This avoids duplicate messages if chunks arrive mid-character
                                    if (!toSend.equals(lastMessageRef.get())) {
                                        sendMessageToMinecraft(source, toSend);
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
                                sendMessageToMinecraft(source, currentMessage.toString());
                                currentMessage.setLength(0);
                            }
                        }
                        // Handle content_block_stop event to display message if we have a complete block
                        else if (json.has("type") && "content_block_stop".equals(json.get("type").getAsString())) {
                            if (currentMessage.length() > 0 && currentMessage.length() < MAX_MESSAGE_LENGTH) {
                                sendMessageToMinecraft(source, currentMessage.toString());
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
    
    private int findBreakPoint(String text, int maxLength) {
        // If text is shorter than maxLength, return its length
        if (text.length() <= maxLength) {
            return text.length();
        }
        
        // Try to break at a sentence end
        int lastPeriod = text.lastIndexOf('.', maxLength);
        if (lastPeriod > maxLength - 30) {
            return lastPeriod + 1;
        }
        
        // Try to break at a comma
        int lastComma = text.lastIndexOf(',', maxLength);
        if (lastComma > maxLength - 20) {
            return lastComma + 1;
        }
        
        // Fall back to a space
        int lastSpace = text.lastIndexOf(' ', maxLength);
        if (lastSpace > 0) {
            return lastSpace + 1;
        }
        
        // If no good break point, just break at maxLength
        return maxLength;
    }
    
    private void sendMessageToMinecraft(ServerCommandSource source, String message) {
        // Trim whitespace and ensure the message isn't empty
        final String finalMessage = message.trim();
        if (!finalMessage.isEmpty()) {
            // Send the message on the main game thread
            source.getServer().execute(() -> {
                source.sendFeedback(() -> Text.literal(finalMessage), false);
            });
        }
    }
    
    private String fetchFromLLM(String query) throws Exception {
        // Fallback non-streaming method
        String apiUrl = "https://api.anthropic.com/v1/messages";
        String apiKey = config.anthropicApiKey;
        String requestBody = String.format(
            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"max_tokens\":2000}",
            config.anthropicModel, 
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
    }
    
    private String[] splitMessage(String message) {
        // Split message if longer than Minecraft's text limit
        int maxLength = MAX_MESSAGE_LENGTH;
        int messageCount = (message.length() + maxLength - 1) / maxLength;
        String[] result = new String[messageCount];
        
        for (int i = 0; i < messageCount; i++) {
            int start = i * maxLength;
            int end = Math.min((i + 1) * maxLength, message.length());
            result[i] = message.substring(start, end);
        }
        
        return result;
    }
    
    private Config loadConfig() {
        Path configDir = Paths.get("config");
        Path configFile = configDir.resolve("llmcommand.json");
        
        try {
            // Create config directory if it doesn't exist
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                log("Created config directory at: " + configDir);
            }
            
            Config config = new Config();
            
            // Load existing config if it exists
            if (Files.exists(configFile)) {
                log("Loading config from: " + configFile);
                JsonObject json = JsonParser.parseReader(new FileReader(configFile.toFile())).getAsJsonObject();
                config.anthropicApiKey = json.get("anthropicApiKey").getAsString();
                config.anthropicModel = json.get("anthropicModel").getAsString();
                
                // Load system prompt if it exists
                if (json.has("systemPrompt")) {
                    config.systemPrompt = json.get("systemPrompt").getAsString();
                }
                
                // Load debug mode if it exists
                if (json.has("debugMode")) {
                    config.debugMode = json.get("debugMode").getAsBoolean();
                }
                
                // Load OpenAI configs if they exist
                if (json.has("openaiApiKey")) {
                    config.openaiApiKey = json.get("openaiApiKey").getAsString();
                }
                if (json.has("openaiModel")) {
                    config.openaiModel = json.get("openaiModel").getAsString();
                }
                
                // Load Gemini configs if they exist
                if (json.has("geminiApiKey")) {
                    config.geminiApiKey = json.get("geminiApiKey").getAsString();
                }
                if (json.has("geminiModel")) {
                    config.geminiModel = json.get("geminiModel").getAsString();
                }
                
                // Load current provider if it exists
                if (json.has("currentProvider")) {
                    config.currentProvider = json.get("currentProvider").getAsString();
                }
            } else {
                // Create default config file
                log("Creating default config file");
                JsonObject json = new JsonObject();
                json.addProperty("anthropicApiKey", "your_anthropic_key_here");
                json.addProperty("anthropicModel", config.anthropicModel);
                json.addProperty("systemPrompt", "You are a helpful Minecraft assistant. Answer questions about Minecraft and provide helpful advice to players. Keep responses concise to fit in the Minecraft chat.");
                json.addProperty("debugMode", false);
                json.addProperty("openaiApiKey", "your_openai_key_here");
                json.addProperty("openaiModel", config.openaiModel);
                json.addProperty("geminiApiKey", "your_gemini_key_here");
                json.addProperty("geminiModel", config.geminiModel);
                json.addProperty("currentProvider", config.currentProvider);
                
                try (FileWriter writer = new FileWriter(configFile.toFile())) {
                    writer.write(json.toString());
                }
                
                log("Created default config file at: " + configFile);
                log("Please edit this file to add your API keys.");
            }
            
            return config;
        } catch (Exception e) {
            log("Error loading config: " + e.getMessage());
            e.printStackTrace();
            return new Config(); // Return default config
        }
    }
    
    private void registerConfigCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("llmconfig")
                    .then(argument("key", StringArgumentType.word())
                    .then(argument("value", StringArgumentType.greedyString())
                    .executes(this::executeConfigCommand)))
                    .executes(this::displayConfigHelp)
            );
        });
    }
    
    private int displayConfigHelp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        source.sendFeedback(() -> Text.literal("§6LLM Config Help:§r"), false);
        source.sendFeedback(() -> Text.literal("§7Usage: /llmconfig <key> <value>§r"), false);
        source.sendFeedback(() -> Text.literal("§7Available keys:§r"), false);
        source.sendFeedback(() -> Text.literal("§7- currentProvider: Set to 'anthropic', 'openai', or 'gemini'§r"), false);
        source.sendFeedback(() -> Text.literal("§7- anthropicApiKey: Set your Anthropic API key§r"), false);
        source.sendFeedback(() -> Text.literal("§7- anthropicModel: Set the Anthropic model§r"), false);
        source.sendFeedback(() -> Text.literal("§7- openaiApiKey: Set your OpenAI API key§r"), false);
        source.sendFeedback(() -> Text.literal("§7- openaiModel: Set the OpenAI model§r"), false);
        source.sendFeedback(() -> Text.literal("§7- geminiApiKey: Set your Gemini API key§r"), false);
        source.sendFeedback(() -> Text.literal("§7- geminiModel: Set the Gemini model§r"), false);
        source.sendFeedback(() -> Text.literal("§7- systemPrompt: Set the system prompt for the AI§r"), false);
        source.sendFeedback(() -> Text.literal("§7- debugMode: Set to 'true' or 'false'§r"), false);
        
        return Command.SINGLE_SUCCESS;
    }
    
    private int executeConfigCommand(CommandContext<ServerCommandSource> context) {
        String key = StringArgumentType.getString(context, "key");
        String value = StringArgumentType.getString(context, "value");
        ServerCommandSource source = context.getSource();
        
        // Check if the user has the required permission level
        if (!source.hasPermissionLevel(2)) {
            source.sendFeedback(() -> Text.literal("§cYou don't have permission to change the configuration.§r"), false);
            return 0;
        }
        
        boolean validKey = true;
        
        // Update the config based on the key
        switch (key.toLowerCase()) {
            case "currentprovider":
                if (value.equalsIgnoreCase("anthropic") || 
                    value.equalsIgnoreCase("openai") || 
                    value.equalsIgnoreCase("gemini")) {
                    config.currentProvider = value.toLowerCase();
                    source.sendFeedback(() -> Text.literal("§aSet current provider to: " + value + "§r"), false);
                } else {
                    source.sendFeedback(() -> Text.literal("§cInvalid provider. Use 'anthropic', 'openai', or 'gemini'.§r"), false);
                    return 0;
                }
                break;
            case "anthropicapikey":
                config.anthropicApiKey = value;
                source.sendFeedback(() -> Text.literal("§aAnthropicApiKey updated.§r"), false);
                break;
            case "anthropicmodel":
                config.anthropicModel = value;
                source.sendFeedback(() -> Text.literal("§aAnthropicModel set to: " + value + "§r"), false);
                break;
            case "openaikey":
            case "openaiapikey":
                config.openaiApiKey = value;
                source.sendFeedback(() -> Text.literal("§aOpenAIApiKey updated.§r"), false);
                break;
            case "openaimodel":
                config.openaiModel = value;
                source.sendFeedback(() -> Text.literal("§aOpenAIModel set to: " + value + "§r"), false);
                break;
            case "geminikey":
            case "geminiapikey":
                config.geminiApiKey = value;
                source.sendFeedback(() -> Text.literal("§aGeminiApiKey updated.§r"), false);
                break;
            case "geminimodel":
                config.geminiModel = value;
                source.sendFeedback(() -> Text.literal("§aGeminiModel set to: " + value + "§r"), false);
                break;
            case "systemprompt":
                config.systemPrompt = value;
                source.sendFeedback(() -> Text.literal("§aSystemPrompt updated.§r"), false);
                break;
            case "debugmode":
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    config.debugMode = Boolean.parseBoolean(value.toLowerCase());
                    source.sendFeedback(() -> Text.literal("§aDebugMode set to: " + config.debugMode + "§r"), false);
                } else {
                    source.sendFeedback(() -> Text.literal("§cInvalid value for debugMode. Use 'true' or 'false'.§r"), false);
                    return 0;
                }
                break;
            default:
                validKey = false;
                source.sendFeedback(() -> Text.literal("§cInvalid configuration key. Type /llmconfig for help.§r"), false);
                return 0;
        }
        
        if (validKey) {
            // Save the updated config
            saveConfig();
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    private void saveConfig() {
        Path configDir = Paths.get("config");
        Path configFile = configDir.resolve("llmcommand.json");
        
        try {
            JsonObject json = new JsonObject();
            json.addProperty("anthropicApiKey", config.anthropicApiKey);
            json.addProperty("anthropicModel", config.anthropicModel);
            json.addProperty("systemPrompt", config.systemPrompt);
            json.addProperty("debugMode", config.debugMode);
            json.addProperty("openaiApiKey", config.openaiApiKey);
            json.addProperty("openaiModel", config.openaiModel);
            json.addProperty("geminiApiKey", config.geminiApiKey);
            json.addProperty("geminiModel", config.geminiModel);
            json.addProperty("currentProvider", config.currentProvider);
            
            try (FileWriter writer = new FileWriter(configFile.toFile())) {
                writer.write(json.toString());
            }
            
            log("Configuration saved to: " + configFile);
        } catch (Exception e) {
            logError("Error saving config", e);
        }
    }
    
    private void streamFromOpenAILLM(String query, ServerCommandSource source) throws Exception {
        // Set up OpenAI API request
        String apiUrl = "https://api.openai.com/v1/chat/completions";
        String apiKey = config.openaiApiKey;
        
        String requestBodyJson;
        if (config.systemPrompt != null && !config.systemPrompt.isEmpty()) {
            log("Using system prompt: " + config.systemPrompt);
            requestBodyJson = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true,\"max_tokens\":2000}",
                config.openaiModel, 
                config.systemPrompt.replace("\"", "\\\""),
                query.replace("\"", "\\\"")
            );
        } else {
            requestBodyJson = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true,\"max_tokens\":2000}",
                config.openaiModel, 
                query.replace("\"", "\\\"")
            );
        }
        
        log("Sending request to OpenAI API with query: " + query);
        if (config.debugMode) {
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
            if (config.debugMode) {
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
            source.sendFeedback(() -> Text.literal("§cError talking to OpenAI: " + e.getMessage() + "§r"), false);
            throw e;
        }
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
                if (config.debugMode) {
                    log("Received line from OpenAI: " + line);
                }
                
                // Handle OpenAI stream format
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    
                    // Skip [DONE] marker
                    if (data.equals("[DONE]")) {
                        if (currentMessage.length() > 0) {
                            sendMessageToMinecraft(source, currentMessage.toString());
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
                                if (currentMessage.length() >= MAX_MESSAGE_LENGTH) {
                                    // Find a good break point
                                    int breakPoint = findBreakPoint(currentMessage.toString(), MAX_MESSAGE_LENGTH);
                                    
                                    // Extract the part to send
                                    String toSend = currentMessage.substring(0, breakPoint);
                                    
                                    // Only send if different from last message sent
                                    if (!toSend.equals(lastMessageRef.get())) {
                                        sendMessageToMinecraft(source, toSend);
                                        lastMessageRef.set(toSend);
                                    }
                                    
                                    // Keep the remainder for the next message
                                    currentMessage.delete(0, breakPoint);
                                }
                            }
                            
                            // Check for finish_reason to send remaining text
                            if (choice.has("finish_reason") && choice.get("finish_reason").getAsString() != null) {
                                if (currentMessage.length() > 0) {
                                    sendMessageToMinecraft(source, currentMessage.toString());
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
    
    private void streamFromGeminiLLM(String query, ServerCommandSource source) throws Exception {
        // Set up Gemini API request
        String model = config.geminiModel;
        String apiKey = config.geminiApiKey;
        // Note: Using streamGenerateContent even though we read the whole response now.
        // The non-streaming endpoint has a different structure.
        String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?key=" + apiKey;
        
        // Create request body
        String requestBodyJson;
        if (config.systemPrompt != null && !config.systemPrompt.isEmpty()) {
            log("Using system prompt with Gemini: " + config.systemPrompt);
            requestBodyJson = String.format(
                "{\"system_instruction\":{\"parts\":[{\"text\":\"%s\"}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"responseMimeType\":\"text/plain\"}}",
                config.systemPrompt.replace("\"", "\\\""),
                query.replace("\"", "\\\"")
            );
        } else {
            requestBodyJson = String.format(
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"responseMimeType\":\"text/plain\"}}",
                query.replace("\"", "\\\"")
            );
        }
        
        log("Sending request to Gemini API with query: " + query);
        if (config.debugMode) {
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
            
            if (config.debugMode) {
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
                    com.google.gson.JsonArray jsonArray = JsonParser.parseString(responseBody).getAsJsonArray();
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
                            if (config.debugMode) {
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
                    int end = findBreakPoint(finalMessage.substring(start), MAX_MESSAGE_LENGTH);
                    sendMessageToMinecraft(source, finalMessage.substring(start, start + end));
                    start += end;
                }
            } else {
                 // Send a message indicating no content was received if applicable
                 sendMessageToMinecraft(source, "§7(Received empty response from Gemini)§r");
            }

            log("Gemini response processed successfully.");

        } catch (Exception e) {
            logError("Error during Gemini request/processing", e);
            source.sendFeedback(() -> Text.literal("§cError talking to Gemini: " + e.getMessage() + "§r"), false);
            throw e; // Re-throw to be caught by the outer handler in executeLLMCommand
        }
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
    
    // Config class to handle settings
    private static class Config {
        String anthropicApiKey = "";
        String anthropicModel = "claude-3-haiku-20240307";
        String systemPrompt = "You are a helpful Minecraft assistant. Answer questions about Minecraft and provide helpful advice to players. Keep responses concise to fit in the Minecraft chat.";
        boolean debugMode = false;
        
        // New fields for OpenAI
        String openaiApiKey = "";
        String openaiModel = "gpt-4o-mini";
        
        // New fields for Gemini
        String geminiApiKey = "";
        String geminiModel = "gemini-2.0-flash";
        
        // Provider selection
        String currentProvider = "anthropic"; // Default provider: anthropic, openai, or gemini
    }
} 