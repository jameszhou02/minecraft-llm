package minecraft.llm.providers;

import minecraft.llm.config.Config;

/**
 * Factory class for creating LLM providers
 */
public class ProviderFactory {
    
    /**
     * Get the appropriate LLM provider based on the configuration
     */
    public static LLMProvider getProvider(Config config) {
        String providerName = config.getCurrentProvider().toLowerCase();
        
        switch (providerName) {
            case "anthropic":
                return new AnthropicProvider(config);
            case "openai":
                return new OpenAIProvider(config);
            case "gemini":
                return new GeminiProvider(config);
            default:
                // Default to Anthropic if the provider is not recognized
                return new AnthropicProvider(config);
        }
    }
} 