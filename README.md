# Minecraft LLM Command Mod

This Fabric mod allows you to interact with AI language models (LLMs) directly from your Minecraft chat.

## Features

- Support for multiple LLM providers:
  - Anthropic (Claude)
  - OpenAI (GPT models)
  - Google (Gemini)
- Configurable system prompt
- Streaming responses for a chat-like experience
- In-game configuration commands

## Installation

1. Make sure you have [Fabric](https://fabricmc.net/) installed.
2. Download the latest release of the mod from the [Releases](https://github.com/your-username/minecraft-llm/releases) page.
3. Place the JAR file in your Minecraft `mods` folder.
4. Start Minecraft with the Fabric loader.

## Configuration

You need to set up your API keys before using the mod:

1. Launch Minecraft once with the mod installed to generate the default configuration.
2. Edit the config file at: `.minecraft/config/llmcommand.json`
3. Add your API keys for the services you want to use.
4. (Optional) Customize the system prompt, models, or other settings.

Alternatively, you can configure the mod in-game using the `/llmconfig` command (requires operator privileges).

## Usage

To ask a question, use the `/llm` command:

```
/llm What biomes are good for finding diamonds?
```

The AI will think for a moment and then respond in the chat.

## In-game Configuration Commands

The following commands are available for in-game configuration:

```
/llmconfig currentProvider <provider>     # anthropic, openai, or gemini
/llmconfig anthropicApiKey <key>          # Set Anthropic API key
/llmconfig anthropicModel <model>         # Set Anthropic model
/llmconfig openaiApiKey <key>             # Set OpenAI API key
/llmconfig openaiModel <model>            # Set OpenAI model
/llmconfig geminiApiKey <key>             # Set Gemini API key
/llmconfig geminiModel <model>            # Set Gemini model
/llmconfig systemPrompt <prompt>          # Set system prompt
/llmconfig debugMode <true/false>         # Enable/disable debug logging
```

## Default LLM Models

- Anthropic: `claude-3-haiku-20240307`
- OpenAI: `gpt-4o-mini`
- Gemini: `gemini-2.0-flash`

You can change these in the config file or using the commands above.

## Building from Source

1. Clone the repository
2. Run `./gradlew build`
3. The compiled JAR will be in `build/libs/`

## License

This project is released under the MIT License - see the LICENSE file for details.

## Credits

- Built with Fabric API
- Uses the official client libraries for Anthropic, OpenAI, and Google Gemini

## Troubleshooting

If you encounter issues:

1. Check that your API keys are correctly set up
2. Enable debug mode with `/llmconfig debugMode true` to see more detailed logs
3. Check the Minecraft log file for errors

For additional help, open an issue on GitHub.
