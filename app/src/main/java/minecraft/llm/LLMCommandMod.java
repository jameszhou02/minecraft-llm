package minecraft.llm;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

import minecraft.llm.config.Config;
import minecraft.llm.providers.LLMProvider;
import minecraft.llm.providers.ProviderFactory;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class LLMCommandMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("LLMCommandMod");
    private Config config;
    
    @Override
    public void onInitialize() {
        // Load or create config
        this.config = Config.loadConfig();
        registerLLMCommand();
        registerConfigCommand();
        log("LLM Command Mod initialized!");
        log("Using model: " + getCurrentProvider().getCurrentModel());
        log("Current provider: " + config.getCurrentProvider());
        if (config.getDebugMode()) {
            log("Debug mode is enabled - detailed logs will be written to .minecraft/logs/latest.log");
        }
    }
    
    private void log(String message) {
        if (config != null && config.getDebugMode()) {
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
    
    /**
     * Get the current provider based on configuration
     */
    private LLMProvider getCurrentProvider() {
        return ProviderFactory.getProvider(config);
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
                LLMProvider provider = getCurrentProvider();
                
                if (config.getDebugMode()) {
                    // Print info directly to server console for visibility
                    log("[LLMCommandMod] DEBUG: Starting request to " + config.getCurrentProvider() + " with query: " + query);
                }
                
                // Check if the provider has a valid API key
                if (!provider.hasValidApiKey()) {
                    String errorMessage = "§cError: " + provider.getProviderName() + " API key not set. " +
                                         "Please set your " + provider.getProviderName() + " API key in config/llmcommand.json or use /llmconfig§r";
                    source.sendFeedback(() -> Text.literal(errorMessage), false);
                    return;
                }
                
                log("Using model: " + provider.getCurrentModel());
                
                // Use the provider to stream the response
                provider.streamResponse(query, source);
                
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
                    config.setCurrentProvider(value.toLowerCase());
                    source.sendFeedback(() -> Text.literal("§aSet current provider to: " + value + "§r"), false);
                } else {
                    source.sendFeedback(() -> Text.literal("§cInvalid provider. Use 'anthropic', 'openai', or 'gemini'.§r"), false);
                    return 0;
                }
                break;
            case "anthropicapikey":
                config.setAnthropicApiKey(value);
                source.sendFeedback(() -> Text.literal("§aAnthropicApiKey updated.§r"), false);
                break;
            case "anthropicmodel":
                config.setAnthropicModel(value);
                source.sendFeedback(() -> Text.literal("§aAnthropicModel set to: " + value + "§r"), false);
                break;
            case "openaikey":
            case "openaiapikey":
                config.setOpenaiApiKey(value);
                source.sendFeedback(() -> Text.literal("§aOpenAIApiKey updated.§r"), false);
                break;
            case "openaimodel":
                config.setOpenaiModel(value);
                source.sendFeedback(() -> Text.literal("§aOpenAIModel set to: " + value + "§r"), false);
                break;
            case "geminikey":
            case "geminiapikey":
                config.setGeminiApiKey(value);
                source.sendFeedback(() -> Text.literal("§aGeminiApiKey updated.§r"), false);
                break;
            case "geminimodel":
                config.setGeminiModel(value);
                source.sendFeedback(() -> Text.literal("§aGeminiModel set to: " + value + "§r"), false);
                break;
            case "systemprompt":
                config.setSystemPrompt(value);
                source.sendFeedback(() -> Text.literal("§aSystemPrompt updated.§r"), false);
                break;
            case "debugmode":
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    config.setDebugMode(Boolean.parseBoolean(value.toLowerCase()));
                    source.sendFeedback(() -> Text.literal("§aDebugMode set to: " + config.getDebugMode() + "§r"), false);
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
            config.saveConfig();
        }
        
        return Command.SINGLE_SUCCESS;
    }
} 