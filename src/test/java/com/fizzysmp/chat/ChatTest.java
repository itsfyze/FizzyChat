package com.fizzysmp.chat;

import static org.junit.jupiter.api.Assertions.*;
import static com.fizzysmp.chat.Routing.Mode.*;
import static com.fizzysmp.chat.Routing.Delivery.*;
import java.util.*;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;

class ChatTest {
    @TempDir Path directory;
    final UUID world=UUID.randomUUID(), other=UUID.randomUUID();
    Routing.Position position(UUID w,double x,boolean permission) {return new Routing.Position(w,"world",x,0,0,permission);}
    Settings settings() {return Settings.load(new YamlConfiguration());}
    Player player(UUID id) {
        return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()) {
            case "getUniqueId" -> id; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy==args[0]; case "toString" -> id.toString();
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    AsyncChatEvent event(Player sender,Set<Audience> viewers,ChatRenderer renderer) {
        return new AsyncChatEvent(true,sender,new HashSet<>(viewers),renderer,Component.text("hello"),Component.text("hello"),null);
    }
    @Test void radiusIncludesBoundaryButNotBeyondOrOtherWorld() {
        assertEquals(NORMAL,Routing.delivery(LOCAL,false,position(world,0,false),position(world,100,false),100,false));
        assertEquals(NONE,Routing.delivery(LOCAL,false,position(world,0,false),position(world,100.001,false),100,false));
        assertEquals(NONE,Routing.delivery(LOCAL,false,position(world,0,false),position(other,0,false),100,false));
    }
    @Test void radiusUsesVerticalDistanceAndSenderAlwaysReceives() {
        var high=new Routing.Position(world,"world",0,101,0,false);
        assertEquals(NONE,Routing.delivery(LOCAL,false,position(world,0,false),high,100,false));
        assertEquals(NORMAL,Routing.delivery(LOCAL,true,null,null,100,false));
    }
    @Test void globalKeepsAllRecipientsAndExactRenderer() {
        Player a=player(UUID.randomUUID()),b=player(UUID.randomUUID());ChatRenderer renderer=(s,n,m,v)->m;
        var event=event(a,Set.of(a,b,Audience.empty()),renderer);var viewers=Set.copyOf(event.viewers());
        ChatDelivery.apply(event,GLOBAL,settings(),Map.of(),Set.of());
        assertEquals(viewers,event.viewers());assertSame(renderer,event.renderer());
    }
    @Test void localFiltersConsoleDistantAndCrossWorldButIncludesRemoteSpyOnce() {
        Player a=player(UUID.randomUUID()),near=player(UUID.randomUUID()),far=player(UUID.randomUUID()),spy=player(UUID.randomUUID());
        Map<UUID,Routing.Position> pos=Map.of(a.getUniqueId(),position(world,0,false),near.getUniqueId(),position(world,90,true),far.getUniqueId(),position(other,0,false),spy.getUniqueId(),position(other,0,true));
        var event=event(a,Set.of(a,near,far,spy,Audience.empty()),(s,n,m,v)->Component.text("Rank Alice: hello",NamedTextColor.RED));
        ChatDelivery.apply(event,LOCAL,settings(),pos,Set.of(near.getUniqueId(),spy.getUniqueId()));
        assertEquals(Set.of(a,near,spy),event.viewers());
        var plain=PlainTextComponentSerializer.plainText();
        assertEquals("[L] Rank Alice: hello",plain.serialize(event.renderer().render(a,Component.text("Alice"),event.message(),near)));
        assertEquals("[L-SPY] [world] Rank Alice: hello",plain.serialize(event.renderer().render(a,Component.text("Alice"),event.message(),spy)));
    }
    @Test void deniedSpyNeverReceivesRemoteMessages() {
        assertEquals(NONE,Routing.delivery(LOCAL,false,position(world,0,false),position(other,0,false),100,true));
    }
    @Test void cancelledOrMutedChatIsNotRebroadcastOrModified() {
        Player a=player(UUID.randomUUID());ChatRenderer renderer=(s,n,m,v)->m;var event=event(a,Set.of(Audience.empty()),renderer);event.setCancelled(true);
        var before=Set.copyOf(event.viewers());ChatDelivery.apply(event,LOCAL,settings(),Map.of(),Set.of(a.getUniqueId()));
        assertTrue(event.isCancelled());assertSame(renderer,event.renderer());assertEquals(before,event.viewers());
    }
    @Test void oneShotNeverChangesStoredModeEvenWhenCancelledOrThrowing() {
        ChatState state=new ChatState();UUID id=UUID.randomUUID();state.modes.put(id,GLOBAL);
        state.once(LOCAL,()->assertEquals(LOCAL,state.choose(id,GLOBAL)));assertEquals(GLOBAL,state.choose(id,GLOBAL));
        assertThrows(IllegalStateException.class,()->state.once(LOCAL,()->{throw new IllegalStateException();}));
        assertEquals(GLOBAL,state.choose(id,GLOBAL));
        state.modes.put(id,LOCAL);state.once(GLOBAL,()->assertEquals(GLOBAL,state.choose(id,GLOBAL)));assertEquals(LOCAL,state.choose(id,GLOBAL));
    }
    @Test void forcedModesAreThreadLocalAndNestedCallsRestoreTheirParent() throws Exception {
        ChatState state=new ChatState();UUID id=UUID.randomUUID();state.once(LOCAL,()->{
            state.once(GLOBAL,()->assertEquals(GLOBAL,state.choose(id,GLOBAL)));assertEquals(LOCAL,state.choose(id,GLOBAL));
        });
        var result=new java.util.concurrent.atomic.AtomicReference<Routing.Mode>();
        state.once(LOCAL,()->{var thread=new Thread(()->result.set(state.choose(id,GLOBAL)));thread.start();try{thread.join();}catch(InterruptedException e){throw new RuntimeException(e);}});
        assertEquals(GLOBAL,result.get());
    }
    @Test void modeChoicesPersistButSpyDoesNot() throws Exception {
        UUID id=UUID.randomUUID();ChatState first=new ChatState();assertTrue(first.spies.isEmpty());first.modes.put(id,LOCAL);first.spy(id,true,true,true);
        Path file=directory.resolve("modes.yml");ModeStore.save(file,first.modes);
        ChatState restarted=new ChatState();restarted.modes.putAll(ModeStore.load(file));
        assertEquals(LOCAL,restarted.choose(id,GLOBAL));assertTrue(restarted.spies.isEmpty());
        assertFalse(Files.readString(file).contains("spy"));
    }
    @Test void spyIsExplicitPermissionCheckedAndClearedOnQuitReloadRestart() {
        ChatState state=new ChatState();UUID id=UUID.randomUUID();assertFalse(state.spies.contains(id));
        assertFalse(state.spy(id,true,false,true));assertFalse(state.spy(id,true,true,false));assertTrue(state.spy(id,true,true,true));
        state.disconnect(id);assertTrue(state.spies.isEmpty());assertEquals(GLOBAL,state.choose(id,GLOBAL));
        state.spy(id,true,true,true);state.reload();assertTrue(state.spies.isEmpty());assertTrue(new ChatState().spies.isEmpty());
    }
    @Test void localDiscordForwardingIsCancelledForActualApiEvent() {
        var outgoing=new GameChatMessagePreProcessEvent("global","hello",null);
        DiscordBridge.guard(outgoing,LOCAL,true);assertTrue(outgoing.isCancelled());
    }
    @Test void globalDiscordForwardingIsPreserved() {
        var outgoing=new GameChatMessagePreProcessEvent("global","hello",null);
        DiscordBridge.guard(outgoing,GLOBAL,true);assertFalse(outgoing.isCancelled());
    }
    @Test void cancelledSourceCannotReachDiscordEvenWhenBridgeIgnoresChatCancellation() {
        Player a=player(UUID.randomUUID());var source=event(a,Set.of(a),(s,n,m,v)->m);source.setCancelled(true);
        var outgoing=new GameChatMessagePreProcessEvent("global",github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component.text("hello"),a,source);
        DiscordBridge.guard(outgoing,GLOBAL,true);assertTrue(outgoing.isCancelled());
    }
    @Test void discordToggleAndExistingCancellationAreRespected() {
        var disabled=new GameChatMessagePreProcessEvent("global","hello",null);DiscordBridge.guard(disabled,GLOBAL,false);assertTrue(disabled.isCancelled());
        var cancelled=new GameChatMessagePreProcessEvent("global","hello",null);cancelled.setCancelled(true);DiscordBridge.guard(cancelled,GLOBAL,true);assertTrue(cancelled.isCancelled());
    }
    @Test void localPrefixPreservesFormattingAndComponentIdentity() {
        Component body=Component.text("OWNER Alice: hello",NamedTextColor.RED).decorate(TextDecoration.BOLD);
        ChatRenderer original=(s,n,m,v)->body;
        var renderer=ChatRenderers.wrap(LOCAL,original,settings(),"world",v->false);
        var result=renderer.render(null,Component.empty(),Component.empty(),Audience.empty());
        assertTrue(result.children().contains(body));assertSame(original,ChatRenderers.wrap(GLOBAL,original,settings(),"world",v->false));
    }
    @Test void invalidConfigurationIsRejectedWithoutMutatingPriorSettings() {
        var config=new YamlConfiguration();Settings old=Settings.load(config);
        for(Object bad:List.of(-1,0,Double.NaN,"not-a-number")) {config.set("local.radius",bad);assertThrows(IllegalArgumentException.class,()->Settings.load(config));}
        assertEquals(100,old.radius());config.set("local.radius",50);assertEquals(50,Settings.load(config).radius());
        config.set("staff-spy.default-on",true);assertThrows(IllegalArgumentException.class,()->Settings.load(config));
        config.set("staff-spy.default-on",false);config.set("discordsrv.global-only",false);assertThrows(IllegalArgumentException.class,()->Settings.load(config));
    }
    @Test void configChangesReloadAllPrefixesMessagesAndFeatureSwitches() {
        var config=new YamlConfiguration();config.set("local.prefix","&b[Local] &r");config.set("local.radius",20);config.set("messages.local-enabled","&aDone");config.set("actionbar.enabled",false);
        var loaded=Settings.load(config);var plain=PlainTextComponentSerializer.plainText();
        assertEquals("[Local] ",plain.serialize(loaded.localPrefix()));assertEquals("Done",plain.serialize(loaded.message("local-enabled")));assertFalse(loaded.actionbar());assertEquals(20,loaded.radius());
    }
    @Test void malformedSavedModesAreNotOverwritten() throws Exception {
        Path file=directory.resolve("modes.yml");Files.writeString(file,"not-a-uuid: LOCAL\n");String original=Files.readString(file);
        assertThrows(IllegalArgumentException.class,()->ModeStore.load(file));assertEquals(original,Files.readString(file));
    }
    @Test void failedSavePreservesPreviousFileAndCleansTemporaryFile() throws Exception {
        Path target=directory.resolve("blocked");Files.createDirectory(target);Files.writeString(target.resolve("existing"),"keep");
        assertThrows(Exception.class,()->ModeStore.save(target,Map.of(UUID.randomUUID(),LOCAL)));
        assertEquals("keep",Files.readString(target.resolve("existing")));
        try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.getFileName().toString().endsWith(".tmp")));}
    }
    @Test void commandMessagesCannotExecuteCommandsOrInjectChatControlCharacters() {
        assertFalse(ChatInput.valid("/op someone"));assertFalse(ChatInput.valid("\nhello"));assertFalse(ChatInput.valid("\u00a7chello"));
        assertFalse(ChatInput.valid(" "));assertFalse(ChatInput.valid("x".repeat(257)));assertTrue(ChatInput.valid("x".repeat(256)));
        assertTrue(ChatInput.valid("hello &aMinecraft"));
    }
    @Test void defaultConfigIsGlobalAndExplicitSpyOptIn() {
        var s=settings();assertEquals(GLOBAL,s.defaultMode());assertTrue(s.spyEnabled());assertTrue(new ChatState().spies.isEmpty());
        assertEquals(100,s.radius());assertEquals(3,s.duration());assertTrue(s.actionbar());assertTrue(s.saveModes());
    }}

