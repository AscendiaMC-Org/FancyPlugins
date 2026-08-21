package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancyanalytics.logger.properties.StringProperty;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancylib.ReflectionUtils;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent;
import kr.toxicity.model.api.event.hitbox.HitBoxInteractAtEvent;
import kr.toxicity.model.api.platform.PlatformEntity;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CustomModelAttribute {

    public static final String ATTRIBUTE_NAME = "custom_model";

    /**
     * Trackers this plugin has already fully set up (listeners attached, spawned to everyone)
     * during this JVM run. Used to make {@link #setModel(Npc, String)} idempotent: FancyNpcs
     * calls it on every applyAllAttributes(), which happens on every update()/spawn(), i.e. every
     * time any player (re)gains visibility of the NPC - not just when the model actually changes.
     */
    private static final Set<EntityTracker> CONFIGURED_TRACKERS = ConcurrentHashMap.newKeySet();

    /**
     * Number of resource-packs currently downloading for a player (UUID -> count), same pattern
     * as FancyHolograms' PlayerListener. Spawning a model tracker for a player hides their base
     * NPC skin (see BetterModel's EntityHideOption.DEFAULT) and replaces it with the BlockBench
     * model, which only renders once the resource-pack containing its geometry/textures has
     * downloaded. Spawning it beforehand makes the NPC invisible (no skin, no model) until the
     * download finishes, which can take many seconds for a large merged pack. We hold off spawning
     * for a player while their pack(s) are still loading, so they keep seeing the plain NPC skin
     * (already sent independently by FancyNpcs) instead of nothing, and swap to the model the
     * instant the pack resolves - see #onResourcePackStatus and #spawnPendingForPlayer.
     */
    private static final Map<UUID, Integer> LOADING_RESOURCE_PACKS = new ConcurrentHashMap<>();

    public static NpcAttribute getModelAttribute() {
        return new NpcAttribute(
                ATTRIBUTE_NAME,
                () -> BetterModel.modelKeys().stream().toList(),
                List.of(EntityType.PLAYER),
                CustomModelAttribute::setModel
        );
    }

    private static void setModel(Npc npc, String modelName) {
        String npcName = npc.getData().getName();

        Entity bukkitEntity = getBukkitEntity(npc);
        if (bukkitEntity == null) {
            return;
        }

        // FancyNpcs applies attributes (this method) from its own dedicated background thread
        // (FancyNpcsPlugin#getNpcThread(), a plain ScheduledExecutorService - not a Paper/Folia
        // scheduler), every time a player's visibility of the NPC is (re)checked. BetterModel's
        // tracker/registry internals gate work on Folia region-thread-safety (see
        // EntityTrackerRegistry#initialLoad -> adapter().isRegionSafe()), so calling them directly
        // from that foreign thread can silently no-op parts of the spawn (no exception, no visible
        // model) even though hitboxes still work (BetterModel schedules those itself, correctly,
        // via entity.platform().task()). Dispatch onto the correct region thread instead.
        //
        // Entity#getScheduler() doesn't work here: FancyNpcs constructs its NMS entity with `new
        // ServerPlayer(...)` and never calls ServerLevel#addFreshEntity() on it (it's a purely
        // packet-driven fake entity, never registered in the level), so Paper's per-entity
        // scheduler silently never runs the task for it. Use the location-based region scheduler
        // instead, which only needs a valid world + coordinates.
        Bukkit.getRegionScheduler().run(FancyNpcsModelPlugin.get(), npc.getData().getLocation(),
                task -> setModelOnEntityThread(npc, npcName, modelName, bukkitEntity));
    }

    private static void setModelOnEntityThread(Npc npc, String npcName, String modelName, Entity bukkitEntity) {
        // Skip the teardown/recreate below if the requested model is already fully set up.
        // Without this check, every visibility update closes and rebuilds the tracker (and
        // rebroadcasts it to every online player), which flickers the model, floods clients with
        // spawn/remove packets for reused entity ids, and can leave hitbox listeners undone if a
        // rebuild is interrupted by another one - which is what caused the model to sometimes
        // disappear/reappear and required /bettermodel reload to fix interactions.
        EntityTracker currentTracker = getEntityTracker(npc);
        boolean alreadyConfigured = currentTracker != null && !currentTracker.isClosed()
                && CONFIGURED_TRACKERS.contains(currentTracker)
                && currentTracker.name().equalsIgnoreCase(modelName);
        FancyNpcsModelPlugin.get().getFancyLogger().debug(
                "setModel npc=" + npcName + " model=" + modelName + " thread=" + Thread.currentThread().getName()
                        + " currentTracker=" + (currentTracker == null ? "null"
                        : (currentTracker.name() + ",closed=" + currentTracker.isClosed()
                        + ",configuredByUs=" + CONFIGURED_TRACKERS.contains(currentTracker)))
                        + " alreadyConfigured=" + alreadyConfigured
                        + " onlinePlayers=" + Bukkit.getOnlinePlayers().size()
        );
        if (alreadyConfigured) {
            // Still (re)spawn it for every online player, same as the unconditional spawn() used
            // below on first creation - NOT spawnIfNotSpawned(). Server-side "isSpawned" state
            // can be true even when the player's client never actually rendered it (e.g. the
            // player's world/chunks were still loading - common with async-loaded island worlds -
            // when the first spawn packets went out), so trusting it would permanently skip
            // resending to a player who silently never got the model. Unlike the destructive
            // close+recreate this used to do on every call, resending spawn packets for the same,
            // already-existing tracker/entity ids is cheap and side-effect-free for players who
            // did already receive them.
            EntityTrackerRegistry registry = currentTracker.registry();
            int dispatched = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isResourcePackPending(player.getUniqueId())) continue;
                spawnForPlayer(registry, player);
                dispatched++;
            }
            FancyNpcsModelPlugin.get().getFancyLogger().debug(
                    "setModel npc=" + npcName + " already configured, dispatched spawn to " + dispatched + " player(s)"
            );
            return;
        }
        if (currentTracker == null && modelName.equalsIgnoreCase("@none")) {
            return;
        }

        bukkitEntity.customName(Component.empty());

        // Close all existing trackers
        closeAllTrackers(bukkitEntity);

        // remove model if model name is "@none"
        if (modelName.equalsIgnoreCase("@none")) {
            return;
        }

        // Gets or creates entity tracker
        EntityTracker tracker = BetterModel.model(modelName)
                .map(r -> r.getOrCreate(BukkitAdapter.adapt(bukkitEntity)))
                .orElse(null);
        if (tracker == null) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to get model with name " + modelName,
                    StringProperty.of("model_name", modelName),
                    StringProperty.of("npc_name", npcName)
            );
            return;
        }

        CONFIGURED_TRACKERS.add(tracker);
        tracker.handleCloseEvent((closedTracker, reason) -> CONFIGURED_TRACKERS.remove(closedTracker));

        // Scale
        if (npc.getData().getScale() != 1) {
            tracker.scaler(tracker.scaler().multiply(npc.getData().getScale()));
        }

        // Right click on hitbox
        tracker.listenHitBox(HitBoxInteractAtEvent.class, event -> {
            Player player = Bukkit.getPlayer(event.getWho().uuid());
            if (player == null) return;

            npc.interact(player, ActionTrigger.RIGHT_CLICK);
        });

        // Left click on hitbox
        tracker.listenHitBox(HitBoxDamagedEvent.class, event -> {
            PlatformEntity causingEntity = event.getSource().getCausingEntity();
            if (causingEntity == null) return;
            Player player = Bukkit.getPlayer(causingEntity.uuid());
            if (player == null) return;

            npc.interact(player, ActionTrigger.LEFT_CLICK);
        });

        EntityTrackerRegistry registry = tracker.registry();
        int dispatched = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isResourcePackPending(player.getUniqueId())) continue;
            spawnForPlayer(registry, player);
            dispatched++;
        }
        FancyNpcsModelPlugin.get().getFancyLogger().debug(
                "setModel npc=" + npcName + " model=" + modelName + " created new tracker, dispatched spawn to " + dispatched + " player(s)"
                        + " onlinePlayers=" + Bukkit.getOnlinePlayers().size()
        );
    }

    /**
     * Sends the model-spawn packets for a single player, dispatched on that player's own
     * per-entity scheduler - the exact same one FancyNpcs uses to send the NPC's own entity-add
     * packets (see {@code Npc#runOnPlayerScheduler}, which calls {@code player.getScheduler()}).
     * <p>
     * BetterModel's mount packet references the NPC's entity id as its "vehicle"; a client that
     * hasn't processed the NPC's own spawn packet yet doesn't know that id and silently drops it.
     * Dispatching both sends through the same per-player queue guarantees the right order,
     * because FancyNpcs always enqueues its own packet first, synchronously, before our attribute
     * setter even runs.
     * <p>
     * Dispatching by the NPC's own location instead (as tracker setup above does, correctly, for
     * its own state) does NOT give this guarantee: on Folia a player far from the NPC is very
     * likely ticking in a different region than the NPC, so the two sends race on independent
     * threads with no ordering between them at all - which is why only nearby NPCs used to get
     * their model, and repeating the attempt later didn't reliably help either.
     */
    private static void spawnForPlayer(EntityTrackerRegistry registry, Player player) {
        player.getScheduler().run(FancyNpcsModelPlugin.get(), task -> registry.spawn(BukkitAdapter.adapt(player)), null);
    }

    private static boolean isResourcePackPending(UUID playerUuid) {
        return LOADING_RESOURCE_PACKS.containsKey(playerUuid);
    }

    /**
     * Feeds a player's {@link PlayerResourcePackStatusEvent} into the pending-download tracking,
     * and spawns any model tracker that was held back for them once all their packs have resolved
     * (loaded, declined or failed - we don't wait forever on a failed download).
     */
    public static void onResourcePackStatus(Player player, PlayerResourcePackStatusEvent.Status status) {
        UUID uuid = player.getUniqueId();

        if (status == PlayerResourcePackStatusEvent.Status.ACCEPTED) {
            LOADING_RESOURCE_PACKS.merge(uuid, 1, Integer::sum);
        } else {
            LOADING_RESOURCE_PACKS.computeIfPresent(uuid, (_, count) -> count - 1);
        }

        if (LOADING_RESOURCE_PACKS.getOrDefault(uuid, 0) <= 0) {
            LOADING_RESOURCE_PACKS.remove(uuid);
            spawnPendingForPlayer(player);
        }
    }

    public static void clearResourcePackState(UUID playerUuid) {
        LOADING_RESOURCE_PACKS.remove(playerUuid);
    }

    /**
     * Spawns every already-built model tracker for a single player. Used once their resource-pack
     * download resolves, to show what {@link #setModelOnEntityThread} held back earlier while it
     * was still loading.
     */
    public static void spawnPendingForPlayer(Player player) {
        if (isResourcePackPending(player.getUniqueId())) return;

        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            if (!hasAttribute(npc)) continue;

            Entity bukkitEntity = getBukkitEntity(npc);
            if (bukkitEntity == null) continue;

            Bukkit.getRegionScheduler().run(FancyNpcsModelPlugin.get(), npc.getData().getLocation(), task -> {
                EntityTracker tracker = getEntityTracker(npc);
                if (tracker == null || tracker.isClosed()) return;

                spawnForPlayer(tracker.registry(), player);
            });
        }
    }

    private static Entity getBukkitEntity(Npc npc) {
        // get the nms entity object from the Npc implementation classes
        Object nmsEntity = ReflectionUtils.getValue(npc, "npc");
        if (nmsEntity == null) {
            // TODO: create fake nms / bukkit entity object once FancyNpcs itself doesn't store the entity object anymore (when migrated to FancySitula)
            FancyNpcsModelPlugin.get().getFancyLogger().error("Failed to get NMS entity from NPC");
            return null;
        }

        // call the Entity#getBukkitEntity method to get the bukkit entity object
        try {
            return (Entity) ReflectionUtils.getMethod(nmsEntity, "getBukkitEntity").invoke(nmsEntity);
        } catch (IllegalAccessException | InvocationTargetException e) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to invoke getBukkitEntity method on NMS entity",
                    ThrowableProperty.of(e),
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }
    }

    /**
     * Closes all model trackers for the given NPC's entity.
     * This is necessary to prevent old trackers still existing in the world.
     * <p>
     * Callers (e.g. commands) may run on the main thread or any other thread, not necessarily the
     * entity's own Folia region thread - dispatch through the region scheduler for the same reason
     * {@link #setModel(Npc, String)} does.
     */
    public static void closeAllTrackers(Npc npc) {
        Entity bukkitEntity = getBukkitEntity(npc);
        if (bukkitEntity == null) {
            return;
        }

        Bukkit.getRegionScheduler().run(FancyNpcsModelPlugin.get(), npc.getData().getLocation(),
                task -> closeAllTrackers(bukkitEntity));
    }

    private static void closeAllTrackers(Entity bukkitEntity) {
        BetterModel.registry(BukkitAdapter.adapt(bukkitEntity)).ifPresent(reg -> {
            for (EntityTracker tracker : reg.trackers()) {
                tracker.close();
            }
        });
    }

    /**
     * @return whether the given NPC has the model attribute
     */
    public static boolean hasAttribute(Npc npc) {
        for (Map.Entry<NpcAttribute, String> entry : npc.getData().getAttributes().entrySet()) {
            if (entry.getKey().getName().equalsIgnoreCase(ATTRIBUTE_NAME)) {
                return true;
            }
        }

        return false;
    }

    public static EntityTracker getEntityTracker(Npc npc) {
        Entity bukkitEntity = getBukkitEntity(npc);
        if (bukkitEntity == null) {
            return null;
        }

        Optional<EntityTrackerRegistry> trackersOpt = BetterModel.registry(BukkitAdapter.adapt(bukkitEntity));
        if (trackersOpt.isEmpty()) return null;

        Collection<EntityTracker> trackers = trackersOpt.get().trackers();
        if (trackers.isEmpty()) return null;

        return trackers.iterator().next();
    }
}
