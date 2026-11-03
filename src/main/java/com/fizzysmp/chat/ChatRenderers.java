package com.fizzysmp.chat;

import io.papermc.paper.chat.ChatRenderer;
import java.util.function.Predicate;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

final class ChatRenderers {
    static ChatRenderer wrap(Routing.Mode mode,ChatRenderer previous,Settings settings,String world,Predicate<Audience> spying) {
        if(mode==Routing.Mode.GLOBAL)return previous;
        return (source,name,message,viewer)->{
            Component body=previous.render(source,name,message,viewer);
            boolean spy=spying.test(viewer);
            Component output=Component.empty().append(spy?settings.spyPrefix():settings.localPrefix());
            if(spy && settings.showWorld())output=output.append(Component.text("["+world+"] "));
            return output.append(body);
        };
    }
}
