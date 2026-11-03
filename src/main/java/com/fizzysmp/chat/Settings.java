package com.fizzysmp.chat;

import java.util.*;
import org.bukkit.configuration.file.FileConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

record Settings(Routing.Mode defaultMode, boolean saveModes, double radius, Component localPrefix,
                boolean spyEnabled, Component spyPrefix, boolean showWorld, boolean actionbar,
                int duration, boolean discordEnabled, Map<String,Component> messages) {
    static Component color(String value) { return LegacyComponentSerializer.legacyAmpersand().deserialize(value); }
    static Settings load(FileConfiguration c) {
        for(String key:List.of("chat.save-player-modes","staff-spy.enabled","staff-spy.default-on","staff-spy.show-world","actionbar.enabled","discordsrv.enabled","discordsrv.global-only"))
            if(c.contains(key) && !c.isBoolean(key))throw new IllegalArgumentException(key+" must be true or false");
        for(String key:List.of("local.radius","actionbar.duration-seconds"))
            if(c.contains(key) && !(c.get(key) instanceof Number))throw new IllegalArgumentException(key+" must be numeric");
        if(c.contains("actionbar.duration-seconds") && c.getDouble("actionbar.duration-seconds")!=c.getInt("actionbar.duration-seconds"))
            throw new IllegalArgumentException("Actionbar duration must be whole seconds");
        Routing.Mode mode=Routing.Mode.valueOf(c.getString("chat.default-mode","GLOBAL").toUpperCase(Locale.ROOT));
        double radius=c.getDouble("local.radius",100);
        int duration=c.getInt("actionbar.duration-seconds",3);
        if(!Double.isFinite(radius) || radius<=0 || radius>100000 || duration<1 || duration>30)
            throw new IllegalArgumentException("Radius must be 0 < radius <= 100000; actionbar duration 1..30 seconds");
        if(c.getBoolean("staff-spy.default-on",false)) throw new IllegalArgumentException("Staff spy must remain opt-in: default-on must be false");
        if(!c.getBoolean("discordsrv.global-only",true)) throw new IllegalArgumentException("discordsrv.global-only must be true to protect local chat");
        Map<String,Component> messages=new HashMap<>();
        Map<String,String> defaults=Map.ofEntries(
            Map.entry("local-enabled","&aSwitched to local chat."),Map.entry("global-enabled","&bSwitched to global chat."),
            Map.entry("spy-enabled","&eLocal Spy enabled."),Map.entry("spy-disabled","&cLocal Spy disabled."),
            Map.entry("local-actionbar","&aChat Mode: LOCAL"),Map.entry("global-actionbar","&bChat Mode: GLOBAL"),
            Map.entry("spy-on-actionbar","&eLocal Spy: ENABLED"),Map.entry("spy-off-actionbar","&cLocal Spy: DISABLED"),
            Map.entry("no-permission","&cYou do not have permission."),Map.entry("players-only","&cOnly players can use this command."),
            Map.entry("reloaded","&aFizzyChat reloaded. Local spy has been disabled for everyone."),
            Map.entry("spy-unavailable","&cLocal spy is disabled in the configuration."),
            Map.entry("usage-admin","&e/fizzychat reload"),Map.entry("usage-spy","&e/lspy [on|off]"),
            Map.entry("usage-message","&eUsage: /{command} <message up to 256 characters; no commands>"),
            Map.entry("usage-mode","&eUsage: /{command}"),
            Map.entry("reload-failed","&cReload rejected; previous settings remain active: "));
        defaults.forEach((key,value)->messages.put(key,color(c.getString("messages."+key,value))));
        return new Settings(mode,c.getBoolean("chat.save-player-modes",true),radius,color(c.getString("local.prefix","&a[L] &r")),
            c.getBoolean("staff-spy.enabled",true),color(c.getString("staff-spy.prefix","&6[L-SPY] &r")),c.getBoolean("staff-spy.show-world",true),
            c.getBoolean("actionbar.enabled",true),duration,c.getBoolean("discordsrv.enabled",true),Map.copyOf(messages));
    }
    Component message(String name) { return messages.get(name); }
}


