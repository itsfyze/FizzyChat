package com.fizzysmp.chat;

final class ChatInput {
    static boolean valid(String message) {
        return !message.isBlank() && !message.startsWith("/") && message.length()<=256
            && message.codePoints().noneMatch(c->c<32 || c==127 || c==167);
    }
}
