package minecraft.llm.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {
    private static final Logger LOGGER = LoggerFactory.getLogger("LLMCommandMod");
    
    // API keys
    private String anthropicApiKey = "";
    private String openaiApiKey = "";
    private String geminiApiKey = "";
    
    // Models
    private String anthropicModel = "claude-3-haiku-20240307";
    private String openaiModel = "gpt-4o-mini";
    private String geminiModel = "gemini-2.0-flash";
    
    // System prompt
    private String systemPrompt = "You are a helpful Minecraft assistant. Answer questions about Minecraft and provide helpful advice to players. Keep responses concise to fit in the Minecraft chat.";
    
    // Provider selection
    private String currentProvider = "anthropic"; // Default provider: anthropic, openai, or gemini
    
    // Debug mode
    private boolean debugMode = false;
    
    public Config() {
        // Default constructor
    }
    
    // Getters and setters
    public String getAnthropicApiKey() { return anthropicApiKey; }
    public void setAnthropicApiKey(String key) { this.anthropicApiKey = key; }
    
    public String getOpenaiApiKey() { return openaiApiKey; }
    public void setOpenaiApiKey(String key) { this.openaiApiKey = key; }
    
    public String getGeminiApiKey() { return geminiApiKey; }
    public void setGeminiApiKey(String key) { this.geminiApiKey = key; }
    
    public String getAnthropicModel() { return anthropicModel; }
    public void setAnthropicModel(String model) { this.anthropicModel = model; }
    
    public String getOpenaiModel() { return openaiModel; }
    public void setOpenaiModel(String model) { this.openaiModel = model; }
    
    public String getGeminiModel() { return geminiModel; }
    public void setGeminiModel(String model) { this.geminiModel = model; }
    
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }
    
    public String getCurrentProvider() { return currentProvider; }
    public void setCurrentProvider(String provider) { this.currentProvider = provider; }
    
    public boolean getDebugMode() { return debugMode; }
    public void setDebugMode(boolean debug) { this.debugMode = debug; }
    
    // Load config from file
    public static Config loadConfig() {
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
                json.addProperty("systemPrompt", config.systemPrompt);
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
    
    // Save config to file
    public void saveConfig() {
        Path configDir = Paths.get("config");
        Path configFile = configDir.resolve("llmcommand.json");
        
        try {
            JsonObject json = new JsonObject();
            json.addProperty("anthropicApiKey", anthropicApiKey);
            json.addProperty("anthropicModel", anthropicModel);
            json.addProperty("systemPrompt", systemPrompt);
            json.addProperty("debugMode", debugMode);
            json.addProperty("openaiApiKey", openaiApiKey);
            json.addProperty("openaiModel", openaiModel);
            json.addProperty("geminiApiKey", geminiApiKey);
            json.addProperty("geminiModel", geminiModel);
            json.addProperty("currentProvider", currentProvider);
            
            try (FileWriter writer = new FileWriter(configFile.toFile())) {
                writer.write(json.toString());
            }
            
            log("Configuration saved to: " + configFile);
        } catch (Exception e) {
            logError("Error saving config", e);
        }
    }
    
    private static void log(String message) {
        LOGGER.info(message);
    }
    
    private static void logError(String message, Throwable error) {
        if (error != null) {
            LOGGER.error(message, error);
        } else {
            LOGGER.error(message);
        }
    }
} 