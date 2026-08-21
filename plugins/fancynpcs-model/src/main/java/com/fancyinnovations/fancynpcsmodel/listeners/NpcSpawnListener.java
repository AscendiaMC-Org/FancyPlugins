package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import de.oliver.fancynpcs.api.events.NpcSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class NpcSpawnListener implements Listener {

    // MONITOR + ignoreCancelled: if another plugin cancels the spawn, FancyNpcs never sends the
    // NPC to this player and never re-applies attributes for them either, so there would be
    // nothing to drain this entry - don't record it in the first place.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcSpawn(NpcSpawnEvent event) {
        if (!CustomModelAttribute.hasAttribute(event.getNpc())) {
            return;
        }

        CustomModelAttribute.onNpcSpawn(event.getNpc(), event.getPlayer());
    }

}
