package com.fizzysmp.chat;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

final class ModeStore {
    static Map<UUID,Routing.Mode> load(Path file) throws Exception {
        Map<UUID,Routing.Mode> modes=new HashMap<>();
        if(!Files.exists(file)) return modes;
        var yaml=new YamlConfiguration(); yaml.load(file.toFile());
        for(String id:yaml.getKeys(false)) modes.put(UUID.fromString(id),Routing.Mode.valueOf(Objects.requireNonNull(yaml.getString(id))));
        return modes;
    }
    static void save(Path file, Map<UUID,Routing.Mode> modes) throws Exception {
        var yaml=new YamlConfiguration(); modes.forEach((id,mode)->yaml.set(id.toString(),mode.name()));
        Files.createDirectories(file.getParent()); Path temp=Files.createTempFile(file.getParent(),"modes-",".tmp");
        try {
            Files.writeString(temp,yaml.saveToString());
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
