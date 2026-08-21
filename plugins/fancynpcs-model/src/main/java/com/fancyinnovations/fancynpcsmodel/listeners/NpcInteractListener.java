package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import de.oliver.fancynpcs.api.events.NpcPreInteractEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NpcInteractListener implements Listener {

    /**
     * Cancels FancyNpcs' own base click for modelled NPCs, since {@link CustomModelAttribute}
     * handles those through BetterModel's hitbox events instead (see {@code configureTracker}) -
     * letting both paths fire would double-trigger every interaction (actions, cooldown, etc).
     * <p>
     * Only cancels when a BetterModel hitbox actually exists to take over the click (see
     * {@link CustomModelAttribute#hasActiveHitbox}): a {@code .bbmodel} with no bone that opts into
     * a hitbox gets a tracker (the model renders) but BetterModel never creates a single
     * {@code HitBox} for it, so nothing would ever handle the click - cancelling unconditionally in
     * that case made the NPC permanently unclickable instead of falling back to the base click that
     * already works fine for every other (non-modelled) NPC.
     */
    @EventHandler
    public void onNpcInteract(NpcPreInteractEvent event) {
        if (!CustomModelAttribute.hasAttribute(event.getNpc())) {
            return;
        }

        if (!CustomModelAttribute.hasActiveHitbox(event.getNpc())) {
            return;
        }

        event.setCancelled(true);
    }

}
