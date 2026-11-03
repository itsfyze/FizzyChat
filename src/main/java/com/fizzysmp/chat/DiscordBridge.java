package com.fizzysmp.chat;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;

public final class DiscordBridge {
    private final FizzyChat plugin;
    DiscordBridge(FizzyChat plugin) { this.plugin=plugin; DiscordSRV.api.subscribe(this); }
    @Subscribe(priority=ListenerPriority.HIGHEST)
    public void outgoing(GameChatMessagePreProcessEvent event) {
        if(event.getPlayer()==null) {event.setCancelled(true);return;}

        Routing.Mode mode=plugin.discordMode(event.getTriggeringBukkitEvent(),event.getPlayer().getUniqueId());
        guard(event,mode,plugin.settings.discordEnabled());
    }
    static void guard(GameChatMessagePreProcessEvent event,Routing.Mode mode,boolean enabled) {
        boolean sourceCancelled=event.getTriggeringBukkitEvent() instanceof org.bukkit.event.Cancellable source && source.isCancelled();
        if(Routing.blockDiscord(mode,enabled,event.isCancelled() || sourceCancelled))event.setCancelled(true);
    }
    void close() { DiscordSRV.api.unsubscribe(this); }
}


