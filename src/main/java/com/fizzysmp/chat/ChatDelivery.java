package com.fizzysmp.chat;

import java.util.*;
import org.bukkit.entity.Player;
import io.papermc.paper.event.player.AsyncChatEvent;

final class ChatDelivery {
    static void apply(AsyncChatEvent event,Routing.Mode mode,Settings settings,
                      Map<UUID,Routing.Position> positions,Set<UUID> spies) {
        if(event.isCancelled() || mode==Routing.Mode.GLOBAL)return;
        UUID sender=event.getPlayer().getUniqueId();Routing.Position from=positions.get(sender);
        Map<UUID,Routing.Delivery> recipients=new HashMap<>();
        event.viewers().removeIf(audience->{
            if(!(audience instanceof Player p))return true;
            UUID id=p.getUniqueId();
            var delivery=Routing.delivery(mode,id.equals(sender),from,positions.get(id),settings.radius(),settings.spyEnabled() && spies.contains(id));
            recipients.put(id,delivery);return delivery==Routing.Delivery.NONE;
        });
        event.viewers().add(event.getPlayer());
        event.renderer(ChatRenderers.wrap(mode,event.renderer(),settings,from==null?"unknown":from.worldName(),
            viewer->viewer instanceof Player p && recipients.get(p.getUniqueId())==Routing.Delivery.SPY));
    }
}
