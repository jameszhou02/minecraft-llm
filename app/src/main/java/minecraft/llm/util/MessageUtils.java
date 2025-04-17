package minecraft.llm.util;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class MessageUtils {
    private static final int MAX_MESSAGE_LENGTH = 250; // Minecraft's limit is around 256, using 250 to be safe
    
    /**
     * Splits a message if it's longer than Minecraft's text limit
     */
    public static String[] splitMessage(String message) {
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
    
    /**
     * Finds a natural break point in text for splitting
     */
    public static int findBreakPoint(String text, int maxLength) {
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
    
    /**
     * Sends a message to the Minecraft chat
     */
    public static void sendMessageToMinecraft(ServerCommandSource source, String message) {
        // Trim whitespace and ensure the message isn't empty
        final String finalMessage = message.trim();
        if (!finalMessage.isEmpty()) {
            // Send the message on the main game thread
            source.getServer().execute(() -> {
                source.sendFeedback(() -> Text.literal(finalMessage), false);
            });
        }
    }
    
    /**
     * Gets the maximum message length for Minecraft chat
     */
    public static int getMaxMessageLength() {
        return MAX_MESSAGE_LENGTH;
    }
} 