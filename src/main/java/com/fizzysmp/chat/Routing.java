package com.fizzysmp.chat;

import java.util.UUID;

final class Routing {
    enum Mode { GLOBAL, LOCAL }
    record Position(UUID world, String worldName, double x, double y, double z, boolean spyAllowed) {}
    enum Delivery { NORMAL, SPY, NONE }
    static Delivery delivery(Mode mode, boolean self, Position from, Position to, double radius, boolean spying) {
        if (mode == Mode.GLOBAL || self) return Delivery.NORMAL;
        if (from != null && to != null && from.world.equals(to.world)) {
            double dx=from.x-to.x, dy=from.y-to.y, dz=from.z-to.z;
            if(dx*dx+dy*dy+dz*dz <= radius*radius) return Delivery.NORMAL;
        }
        return spying && to != null && to.spyAllowed ? Delivery.SPY : Delivery.NONE;
    }
    static boolean blockDiscord(Mode mode, boolean forwardingEnabled, boolean cancelled) {
        return cancelled || !forwardingEnabled || mode == Mode.LOCAL;
    }
}
