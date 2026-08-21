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
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.event.CreateEntityTrackerEvent;
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
import java.lang.reflect.Method;
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

    /**
     * Players a model spawn is owed to, keyed by {@link de.oliver.fancynpcs.api.NpcData#getId()}.
     * Fed by {@link #onNpcSpawn(Npc, Player)}, which listens to FancyNpcs' per-player
     * {@code NpcSpawnEvent} - the only place that actually knows which single player triggered
     * the attribute re-apply that follows (see {@link #setModelOnEntityThread}). Drained (and
     * cleared) the next time that NPC's attribute setter runs, so only the player(s) who just
     * (re)gained visibility get resent the model, instead of every online player.
     */
    private static final Map<String, Set<UUID>> PENDING_SPAWN_TARGETS = new ConcurrentHashMap<>();

    /**
     * Called from {@code NpcSpawnEvent} (fired once per player, at the point FancyNpcs is about
     * to (re)send that player their view of the NPC). Only records the player - the actual
     * spawn-packet dispatch happens later from {@link #setModelOnEntityThread}, on FancyNpcs'
     * own npc-thread, so it lands after the NPC's own add-entity bundle in that player's packet
     * queue (see {@link #spawnForPlayer} for why ordering matters here).
     */
    public static void onNpcSpawn(Npc npc, Player player) {
        // Must add the player inside the atomic compute() call, not via a separate
        // computeIfAbsent().add() (the previous approach): computeIfAbsent() only makes the
        // *lookup/insert* atomic, the .add() on the returned Set happens as a second, unguarded
        // step. setModelOnEntityThread's drain (PENDING_SPAWN_TARGETS.remove(id)) can run in
        // between those two steps on another thread, taking the set away right before .add()
        // lands on it - the player is then added to an orphaned Set no longer reachable from the
        // map, and is never spawned for. compute() performs the read-and-mutate as one atomic,
        // per-key operation, so it can never interleave with a concurrent remove() on that key -
        // this is what caused a model to occasionally never reappear for a returning player.
        PENDING_SPAWN_TARGETS.compute(npc.getData().getId(), (key, existing) -> {
            Set<UUID> targets = existing != null ? existing : ConcurrentHashMap.newKeySet();
            targets.add(player.getUniqueId());
            return targets;
        });
    }

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
            // This setter has no idea which single player's visibility check triggered this call
            // (FancyNpcs' NpcAttribute only passes (Npc, value), see class javadoc on
            // PENDING_SPAWN_TARGETS) - so instead of guessing, only (re)spawn for whichever
            // player(s) NpcSpawnListener recorded via onNpcSpawn() since the last drain. That is
            // exactly the player(s) currently (re)gaining visibility of this NPC; everyone else
            // already has the model and resending to them would just make their client rebuild it
            // (visible flicker/"reload") for no reason.
            //
            // Still an unconditional spawn(), NOT spawnIfNotSpawned() - server-side "isSpawned"
            // state can be true even when the player's client never actually rendered it (e.g.
            // the player's world/chunks were still loading when the first spawn packets went
            // out), so trusting it would permanently skip resending to a player who silently
            // never got the model.
            Set<UUID> targets = PENDING_SPAWN_TARGETS.remove(npc.getData().getId());
            if (targets == null || targets.isEmpty()) {
                return;
            }

            EntityTrackerRegistry registry = currentTracker.registry();
            int dispatched = 0;
            for (UUID uuid : targets) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || isResourcePackPending(uuid)) continue;
                spawnForPlayer(registry, player);
                dispatched++;
            }
            FancyNpcsModelPlugin.get().getFancyLogger().debug(
                    "setModel npc=" + npcName + " already configured, dispatched spawn to " + dispatched + "/" + targets.size() + " pending target(s)"
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

        // Usually already done by the CreateEntityTrackerEvent subscription (see
        // #registerTrackerCreationListener), which fires synchronously inside getOrCreate() above,
        // before this line even runs. Guarded here too in case that ever isn't true, so this NPC's
        // tracker is never left without hitbox listeners.
        if (!CONFIGURED_TRACKERS.contains(tracker)) {
            configureTracker(npc, tracker);
        }

        // The model itself just changed (or was created), so every currently online player needs
        // the new spawn packets - not just whoever's visibility triggered this call. Any player(s)
        // NpcSpawnListener had queued up are covered by this broadcast too, so drop them.
        PENDING_SPAWN_TARGETS.remove(npc.getData().getId());

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
     * Subscribes to BetterModel's own {@code CreateEntityTrackerEvent} so this NPC's hitbox
     * listeners get (re)attached no matter who created the tracker.
     * <p>
     * Previously, hitbox listeners were only attached inline, right after <em>this plugin's own</em>
     * {@code getOrCreate()} call in {@link #setModelOnEntityThread}. But BetterModel can also
     * create a fresh {@code EntityTracker} for the same entity entirely on its own (e.g. on
     * {@code /bettermodel reload}, or internal recovery/recreation) - a path this plugin never
     * gets a callback for, since {@link #setModel(Npc, String)} only runs when FancyNpcs
     * re-applies attributes. When that happened, the new tracker rendered the model fine (that's
     * driven by BetterModel itself) but had no hitbox listeners at all, so every model NPC would
     * stop responding to interaction at once until an admin ran {@code /bettermodel reload} -
     * which is exactly what this subscription now does automatically, for every NPC, every time.
     */
    public static void registerTrackerCreationListener(FancyNpcsModelPlugin plugin) {
        BetterModelBukkit.platform().eventBus().subscribe(plugin, CreateEntityTrackerEvent.class, event -> {
            // This runs synchronously *inside* BetterModel's own EntityTracker constructor (see
            // that class - the event is fired as the very last constructor statement), which in
            // turn can run synchronously inside our own getOrCreate() call in
            // setModelOnEntityThread. Never let anything here throw: an uncaught exception would
            // propagate out of that constructor and abort getOrCreate() for whoever's waiting on
            // it - including our own setModel(), which would then never reach its "broadcast the
            // new tracker to every online player" step below. That looked exactly like "some NPCs
            // never get a model at all" when it happened.
            try {
                EntityTracker tracker = event.tracker();
                if (CONFIGURED_TRACKERS.contains(tracker)) {
                    return;
                }

                Npc npc = findNpcForTracker(tracker);
                if (npc == null) {
                    return;
                }

                configureTracker(npc, tracker);
            } catch (Throwable t) {
                FancyNpcsModelPlugin.get().getFancyLogger().error(
                        "Failed to configure a newly created BetterModel tracker",
                        ThrowableProperty.of(t)
                );
            }
        });
    }

    /**
     * Finds the FancyNpcs NPC (with the model attribute) whose entity backs the given tracker, or
     * null if it doesn't belong to one - e.g. the server uses BetterModel for something other than
     * this plugin's NPCs.
     */
    private static Npc findNpcForTracker(EntityTracker tracker) {
        UUID entityUuid = tracker.sourceEntity().uuid();
        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            if (!hasAttribute(npc)) continue;

            Entity bukkitEntity = getBukkitEntity(npc);
            if (bukkitEntity != null && bukkitEntity.getUniqueId().equals(entityUuid)) {
                return npc;
            }
        }
        return null;
    }

    /**
     * One-time setup for a freshly created tracker: registers it as configured (idempotency guard
     * used by {@link #setModelOnEntityThread}), applies the NPC's scale, and wires up hitbox
     * click listeners. Called from exactly one of two places for any given tracker - whichever
     * gets there first, see {@link #registerTrackerCreationListener} - never both, so listeners
     * are never registered twice on the same tracker (which would double-fire interactions).
     */
    private static void configureTracker(Npc npc, EntityTracker tracker) {
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

        // Drop any spawn this player was still owed - they're gone, and leaving the UUID behind
        // would just sit in PENDING_SPAWN_TARGETS forever (nothing else ever removes single
        // entries from it, only whole-NPC drains).
        for (Set<UUID> targets : PENDING_SPAWN_TARGETS.values()) {
            targets.remove(playerUuid);
        }
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
        Method getBukkitEntityMethod = ReflectionUtils.getMethod(nmsEntity, "getBukkitEntity");
        if (getBukkitEntityMethod == null) {
            FancyNpcsModelPlugin.get().getFancyLogger().error(
                    "Failed to find getBukkitEntity method on NMS entity",
                    StringProperty.of("npc_name", npc.getData().getName())
            );
            return null;
        }

        try {
            return (Entity) getBukkitEntityMethod.invoke(nmsEntity);
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
