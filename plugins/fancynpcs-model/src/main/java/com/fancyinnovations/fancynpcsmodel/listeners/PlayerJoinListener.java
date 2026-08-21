package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.TimeUnit;

public class PlayerJoinListener implements Listener {

    // See CustomModelAttribute#forceResyncForPlayer for why this delayed, unconditional resend is
    // needed on top of the normal spawn path and the periodic reconcileVisibility safety net.
    //
    // Run twice: this is mitigating a probabilistic packet-loss-under-burst issue (confirmed live -
    // it still hit a different pair of NPCs even after adding the first resync pass), not a
    // deterministic one, so a single attempt isn't reliable enough on its own. The two passes are
    // far enough apart that they don't recreate the same burst themselves (each pass is already
    // staggered internally - see forceResyncForPlayer).
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        scheduleResync(player, 3L);
        scheduleResync(player, 10L);
    }

    private void scheduleResync(Player player, long delaySeconds) {
        Bukkit.getAsyncScheduler().runDelayed(FancyNpcsModelPlugin.get(), task -> {
            if (!player.isOnline()) return;

            try {
                CustomModelAttribute.forceResyncForPlayer(player);
            } catch (Throwable t) {
                FancyNpcsModelPlugin.get().getFancyLogger().error(
                        "Failed to force-resync models on join for player " + player.getName(),
                        ThrowableProperty.of(t)
                );
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

}
