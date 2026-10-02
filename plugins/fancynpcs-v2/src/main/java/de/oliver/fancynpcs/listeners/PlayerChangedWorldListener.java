package de.oliver.fancynpcs.listeners;

import de.oliver.fancynpcs.FancyNpcs;
import de.oliver.fancynpcs.api.Npc;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PlayerChangedWorldListener implements Listener {

    /**
     * Time for the client to finish loading the new world: NPCs sent while it is still on the
     * loading screen could be lost, and the per-player "already shown" mark then kept them hidden
     * for good (players coming back from another world saw none of the NPCs of the first one).
     */
    private static final long RESYNC_DELAY_MS = 500;

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        for (Npc npc : FancyNpcs.getInstance().getNpcManager().getAllNpcs()) {
            npc.checkAndUpdateVisibility(player);
        }

        // Resync once the world is loaded: forget what was marked as shown and send it again
        UUID uuid = player.getUniqueId();
        FancyNpcs.getInstance().getNpcThread().schedule(() -> {
            if (!player.isOnline()) return;
            for (Npc npc : FancyNpcs.getInstance().getNpcManager().getAllNpcs()) {
                npc.getIsVisibleForPlayer().remove(uuid);
                npc.checkAndUpdateVisibility(player);
            }
        }, RESYNC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

}
