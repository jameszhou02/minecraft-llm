package minecraft.llm.providers;

import net.minecraft.server.command.ServerCommandSource;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for all LLM providers
 */
public interface LLMProvider {
    /**
     * Gets the name of the provider
     */
    String getProviderName();
    
    /**
     * Gets the current model being used by this provider
     */
    String getCurrentModel();
    
    /**
     * Sets the model to use for this provider
     */
    void setModel(String model);
    
    /**
     * Checks if this provider has a valid API key configured
     */
    boolean hasValidApiKey();
    
    /**
     * Sends a query to the LLM and streams the response to the user
     */
    CompletableFuture<Void> streamResponse(String query, ServerCommandSource source);
    
    /**
     * Non-streaming fallback method if streaming is not available
     */
    CompletableFuture<String> getResponse(String query);
} 