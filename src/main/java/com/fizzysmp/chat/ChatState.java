package com.fizzysmp.chat;

import java.util.*;
import java.util.concurrent.*;

final class ChatState {
    final Map<UUID,Routing.Mode> modes=new ConcurrentHashMap<>();
    final Set<UUID> spies=ConcurrentHashMap.newKeySet();
    final ThreadLocal<Routing.Mode> forced=new ThreadLocal<>();
    Routing.Mode choose(UUID id,Routing.Mode fallback) {
        Routing.Mode override=forced.get();return override==null?modes.getOrDefault(id,fallback):override;
    }
    void once(Routing.Mode mode,Runnable chat) {
        Routing.Mode previous=forced.get();forced.set(mode);
        try {chat.run();} finally {if(previous==null)forced.remove();else forced.set(previous);}
    }
    boolean spy(UUID id,boolean requested,boolean permitted,boolean featureEnabled) {
        if(requested && permitted && featureEnabled)spies.add(id);else spies.remove(id);
        return spies.contains(id);
    }
    void disconnect(UUID id) {spies.remove(id);}
    void reload() {spies.clear();}
}
