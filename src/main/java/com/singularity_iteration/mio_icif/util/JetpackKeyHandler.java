// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.player.Player;

/** Server-side snapshot of the five jetpack input bits. */
public class JetpackKeyHandler {
    private static final int JUMP = 1 << 0;
    private static final int BOOST = 1 << 1;
    private static final int FORWARD = 1 << 2;
    private static final int SNEAK = 1 << 3;
    private static final int MODE = 1 << 4;
    private static final int VALID_MASK = JUMP | BOOST | FORWARD | SNEAK | MODE;
    private static final Map<UUID, KeyState> STATES = new ConcurrentHashMap<>();

    public static void processKeyUpdate(Player player, int keyState) {
        if (player == null) return;
        STATES.put(player.getUUID(), KeyState.fromInt(keyState & VALID_MASK));
    }

    private static KeyState state(Player player) {
        return player == null ? new KeyState() : STATES.getOrDefault(player.getUUID(), new KeyState());
    }

    public static boolean isJumpKeyDown(Player player) { return state(player).jump; }
    public static boolean isForwardKeyDown(Player player) { return state(player).forward; }
    public static boolean isSneakKeyDown(Player player) { return state(player).sneak; }
    public static boolean isModeSwitchKeyDown(Player player) { return state(player).modeSwitch; }
    public static boolean isAltKeyDown(Player player) { return state(player).modeSwitch; }
    public static boolean isBoostKeyDown(Player player) { return state(player).sprint; }

    public static void removePlayer(Player player) {
        if (player != null) STATES.remove(player.getUUID());
    }

    public static class KeyState {
        public boolean jump;
        public boolean forward;
        public boolean sneak;
        public boolean modeSwitch;
        public boolean sprint;

        public int toInt() {
            int result = 0;
            if (jump) result |= JUMP;
            if (sprint) result |= BOOST;
            if (forward) result |= FORWARD;
            if (sneak) result |= SNEAK;
            if (modeSwitch) result |= MODE;
            return result;
        }

        public static KeyState fromInt(int bits) {
            KeyState state = new KeyState();
            state.jump = (bits & JUMP) != 0;
            state.sprint = (bits & BOOST) != 0;
            state.forward = (bits & FORWARD) != 0;
            state.sneak = (bits & SNEAK) != 0;
            state.modeSwitch = (bits & MODE) != 0;
            return state;
        }
    }
}
