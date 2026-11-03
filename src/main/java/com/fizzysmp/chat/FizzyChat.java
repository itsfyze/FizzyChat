package com.fizzysmp.chat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class FizzyChat extends JavaPlugin implements Listener, TabExecutor {
    volatile Settings settings;
    private final ChatState state=new ChatState();
    private final Map<UUID,Routing.Mode> modes=state.modes;
    private final Set<UUID> spies=state.spies;
    private final Map<UUID,Routing.Position> positions=new ConcurrentHashMap<>();
    private final Map<Event,Routing.Mode> eventModes=Collections.synchronizedMap(new WeakHashMap<>());
    private record Frame(UUID sender,String original,Routing.Mode mode) {}
    private final ThreadLocal<Frame> legacyFrame=new ThreadLocal<>();
    private final ThreadLocal<Routing.Mode> oneShot=state.forced;
    private final Map<UUID,BukkitTask> bars=new HashMap<>();
    private ExecutorService writer;
    private Future<?> pendingSave;
    private volatile boolean dirty;
    private DiscordBridge bridge;
    private Path modeFile;

    @Override public void onEnable() {
        saveDefaultConfig(); modeFile=getDataFolder().toPath().resolve("modes.yml");
        try {
            settings=Settings.load(getConfig());
            if(settings.saveModes()) modes.putAll(ModeStore.load(modeFile));
        } catch(Exception error) {
            getLogger().log(Level.SEVERE,"Invalid configuration or saved modes; files have been preserved. FizzyChat cannot start.",error);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        writer=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"FizzyChat-mode-save");thread.setDaemon(true);return thread;});
        Bukkit.getPluginManager().registerEvents(this,this);
        for(String name:List.of("local","global","lc","gc","localspy","fizzychat")) {
            var command=Objects.requireNonNull(getCommand(name)); command.setExecutor(this); command.setTabCompleter(this);
        }
        for(Player p:Bukkit.getOnlinePlayers()) snapshot(p);
        Bukkit.getScheduler().runTaskTimer(this,()->{for(Player p:Bukkit.getOnlinePlayers())snapshot(p);},5,5);
        Bukkit.getScheduler().runTaskTimer(this,this::saveIfDirty,100,100);
        attachDiscord();
    }
    private Routing.Mode mode(UUID id) { return state.choose(id,settings.defaultMode()); }
    private void snapshot(Player player) {
        var l=player.getLocation();
        positions.put(player.getUniqueId(),new Routing.Position(l.getWorld().getUID(),l.getWorld().getName(),l.getX(),l.getY(),l.getZ(),player.hasPermission("fizzychat.localspy")));
        if(!player.hasPermission("fizzychat.localspy")) spies.remove(player.getUniqueId());
    }
    private void attachDiscord() {
        if(bridge==null && Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            try { bridge=new DiscordBridge(this); }
            catch(LinkageError | RuntimeException error) {
                getLogger().log(Level.SEVERE,"DiscordSRV privacy integration failed; disabling FizzyChat rather than risking local chat forwarding.",error);
                Bukkit.getPluginManager().disablePlugin(this);
            }
        }
    }
    @EventHandler public void enabled(PluginEnableEvent e) { if(e.getPlugin().getName().equals("DiscordSRV"))attachDiscord(); }
    @EventHandler public void disabled(PluginDisableEvent e) {
        if(e.getPlugin().getName().equals("DiscordSRV") && bridge!=null) { bridge.close();bridge=null; }
    }
    @EventHandler public void join(PlayerJoinEvent e) { snapshot(e.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void move(PlayerMoveEvent e) {
        var l=e.getTo(); if(l==null || !e.hasChangedPosition())return;
        positions.put(e.getPlayer().getUniqueId(),new Routing.Position(l.getWorld().getUID(),l.getWorld().getName(),l.getX(),l.getY(),l.getZ(),e.getPlayer().hasPermission("fizzychat.localspy")));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e) { move(e); }
    @EventHandler public void world(PlayerChangedWorldEvent e) { snapshot(e.getPlayer()); }
    @EventHandler public void respawn(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent e) { snapshot(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id=e.getPlayer().getUniqueId();positions.remove(id);state.disconnect(id);cancelBar(id);saveIfDirty();
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.LOWEST)
    public void legacy(AsyncPlayerChatEvent event) {
        Routing.Mode chosen=oneShot.get(); if(chosen==null)chosen=mode(event.getPlayer().getUniqueId());
        eventModes.put(event,chosen);
        legacyFrame.set(new Frame(event.getPlayer().getUniqueId(),event.getMessage(),chosen));
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void prepare(AsyncChatEvent event) {
        Frame frame=legacyFrame.get(); legacyFrame.remove();
        Routing.Mode chosen=oneShot.get();
        if(chosen==null && frame!=null && frame.sender.equals(event.getPlayer().getUniqueId())
                && frame.original.equals(PlainTextComponentSerializer.plainText().serialize(event.originalMessage()))) chosen=frame.mode;
        if(chosen==null)chosen=mode(event.getPlayer().getUniqueId());
        eventModes.put(event,chosen);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void chat(AsyncChatEvent event) {
        ChatDelivery.apply(event,eventModes.getOrDefault(event,Routing.Mode.LOCAL),settings,positions,spies);
    }    Routing.Mode discordMode(Event event,UUID sender) {
        Routing.Mode known=event==null?null:eventModes.get(event);
        // Unknown custom bridge origins fail closed; normal legacy and Paper events are explicitly recorded.
        return known==null?Routing.Mode.LOCAL:known;
    }
    private void cancelBar(UUID id) { BukkitTask old=bars.remove(id);if(old!=null)old.cancel(); }
    private void confirm(Player p,String chat,String action) {
        p.sendMessage(settings.message(chat)); cancelBar(p.getUniqueId());
        if(!settings.actionbar())return;
        Component text=settings.message(action);p.sendActionBar(text);
        int[] ticks={0};
        bars.put(p.getUniqueId(),Bukkit.getScheduler().runTaskTimer(this,()->{
            ticks[0]+=10;
            if(!p.isOnline() || ticks[0]>=settings.duration()*20) {if(p.isOnline())p.sendActionBar(Component.empty());cancelBar(p.getUniqueId());}
            else p.sendActionBar(text);
        },10,10));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        String name=command.getName();String permission=name.equals("fizzychat")?"fizzychat.admin":name.equals("localspy")?"fizzychat.localspy":"fizzychat.use";
        if(!sender.hasPermission(permission)){sender.sendMessage(settings.message("no-permission"));return true;}
        if(name.equals("fizzychat")) {
            if(args.length!=1 || !args[0].equalsIgnoreCase("reload")){sender.sendMessage(settings.message("usage-admin"));return true;}
            try {
                var yaml=new YamlConfiguration();yaml.load(getDataFolder().toPath().resolve("config.yml").toFile());
                Settings next=Settings.load(yaml); settings=next;state.reload();
                bars.values().forEach(BukkitTask::cancel);bars.clear();
                for(Player p:Bukkit.getOnlinePlayers())snapshot(p);
                sender.sendMessage(next.message("reloaded"));
            }catch(Exception error){sender.sendMessage(settings.message("reload-failed").append(Component.text(error.getMessage())));}
            return true;
        }
        if(!(sender instanceof Player player)){sender.sendMessage(settings.message("players-only"));return true;}
        UUID id=player.getUniqueId();snapshot(player);
        if(name.equals("localspy")) {
            if(!settings.spyEnabled()){player.sendMessage(settings.message("spy-unavailable"));return true;}
            if(args.length>1 || args.length==1 && !Set.of("on","off").contains(args[0].toLowerCase(Locale.ROOT))){player.sendMessage(settings.message("usage-spy"));return true;}
            boolean enabled=args.length==0?!spies.contains(id):args[0].equalsIgnoreCase("on");
            enabled=state.spy(id,enabled,player.hasPermission("fizzychat.localspy"),settings.spyEnabled());
            confirm(player,enabled?"spy-enabled":"spy-disabled",enabled?"spy-on-actionbar":"spy-off-actionbar");return true;
        }
        Routing.Mode chosen=name.equals("local") || name.equals("lc")?Routing.Mode.LOCAL:Routing.Mode.GLOBAL;
        if(name.equals("lc") || name.equals("gc")) {
            String message=String.join(" ",args);
            if(!ChatInput.valid(message)) {player.sendMessage(settings.message("usage-message").replaceText(b->b.matchLiteral("{command}").replacement(name)));return true;}
            state.once(chosen,()->{try {player.chat(message);}finally {legacyFrame.remove();}});
            return true;
        }
        if(args.length!=0){player.sendMessage(settings.message("usage-mode").replaceText(b->b.matchLiteral("{command}").replacement(label)));return true;}
        modes.put(id,chosen);dirty=true;
        confirm(player,chosen==Routing.Mode.LOCAL?"local-enabled":"global-enabled",chosen==Routing.Mode.LOCAL?"local-actionbar":"global-actionbar");return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length!=1)return List.of();
        List<String> options=command.getName().equals("fizzychat") && sender.hasPermission("fizzychat.admin")?List.of("reload"):
            command.getName().equals("localspy") && sender.hasPermission("fizzychat.localspy")?List.of("on","off"):List.of();
        return options.stream().filter(value->value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
    private void saveIfDirty() {
        if(!dirty || !settings.saveModes() || pendingSave!=null && !pendingSave.isDone())return;
        Map<UUID,Routing.Mode> snapshot=Map.copyOf(modes);dirty=false;
        pendingSave=writer.submit(()->{
            try { ModeStore.save(modeFile,snapshot); }
            catch(Exception error){getLogger().log(Level.SEVERE,"Could not save modes; previous disk file is preserved",error);dirty=true;}
        });
    }
    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);spies.clear();positions.clear();
        if(bridge!=null){bridge.close();bridge=null;}
        if(writer!=null) {
            if(settings.saveModes()) {Map<UUID,Routing.Mode> snapshot=Map.copyOf(modes);writer.submit(()->{try{ModeStore.save(modeFile,snapshot);}catch(Exception e){getLogger().log(Level.SEVERE,"Final mode save failed",e);}});}
            writer.shutdown();try{if(!writer.awaitTermination(5,TimeUnit.SECONDS))getLogger().warning("Mode save is still finishing after shutdown timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
    }
}





