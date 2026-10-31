package io.github.bakwudo.uyu.extension.danmaku;

import java.util.List;

/**
 * Reads Twitch's chat message objects. Their classes and fields are obfuscated and renamed in
 * every Twitch version, so the Danmaku comments patch replaces the body of each method with code
 * for the classes it finds in the app. Each method returns null, or -1, when the object is not
 * of the expected class.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class ChatMessages {
    private ChatMessages() {
    }

    /**
     * @param liveMessage A ChatLiveMessage.
     * @return Its message id.
     */
    public static String messageId(Object liveMessage) {
        return null;
    }

    /**
     * @param liveMessage A ChatLiveMessage.
     * @return Its ChatMessageInfo.
     */
    public static Object messageInfo(Object liveMessage) {
        return null;
    }

    /**
     * @param messageInfo A ChatMessageInfo.
     * @return The tokens of the message text, in order.
     */
    public static List<?> tokens(Object messageInfo) {
        return null;
    }

    /**
     * @param messageInfo A ChatMessageInfo.
     * @return Its ChatUserInfo (the sender).
     */
    public static Object userInfo(Object messageInfo) {
        return null;
    }

    /**
     * @param messageInfo A ChatMessageInfo.
     * @return When the message was sent (epoch ms), or null.
     */
    public static Long timestamp(Object messageInfo) {
        return null;
    }

    /** @return The login name (lower case) of a ChatUserInfo. */
    public static String userName(Object userInfo) {
        return null;
    }

    /** @return The display name of a ChatUserInfo. */
    public static String displayName(Object userInfo) {
        return null;
    }

    /** @return The user id of a ChatUserInfo, or null. */
    public static String userId(Object userInfo) {
        return null;
    }

    /** @return The text (emote name) of a Twitch emote token. */
    public static String emoteTokenText(Object token) {
        return null;
    }

    /** @return The text of a text token. */
    public static String textTokenText(Object token) {
        return null;
    }

    /** @return The text of a mention token, including the @. */
    public static String mentionTokenText(Object token) {
        return null;
    }

    /** @return The URL of a link token. */
    public static String urlTokenUrl(Object token) {
        return null;
    }

    /** @return The emote id of a Twitch emote token. */
    public static String emoteTokenId(Object token) {
        return null;
    }

    /** @return The cheermote prefix of a Bits token, such as "Cheer". */
    public static String bitsTokenPrefix(Object token) {
        return null;
    }

    /** @return The amount of a Bits token. */
    public static int bitsTokenAmount(Object token) {
        return -1;
    }
}
