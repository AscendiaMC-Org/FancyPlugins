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
import kr.toxicity.model.api.bone.BoneTags;
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.event.CreateEntityTrackerEvent;
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent;
import kr.toxicity.model.api.event.hitbox.HitBoxInteractAtEvent;
import kr.toxicity.model.api.platform.PlatformEntity;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import kr.toxicity.model.api.util.function.BonePredicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
     * Last time (epoch millis) this plugin attempted to recreate a missing/broken tracker for an
     * NPC, keyed by {@link de.oliver.fancynpcs.api.NpcData#getId()}. Used by {@link #ensureTracker}
     * to rate-limit retries - see that method's javadoc for why this exists at all.
     */
    private static final Map<String, Long> LAST_TRACKER_RECREATE_ATTEMPT = new ConcurrentHashMap<>();
    private static final long TRACKER_RECREATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);

    /**
     * The chunk this plugin is currently keeping force-loaded for each NPC (keyed by
     * {@link de.oliver.fancynpcs.api.NpcData#getId()}), via {@link #ensureChunkTicket}. See that
     * method's javadoc for why a model NPC's chunk must stay loaded for as long as it has a
     * tracker.
     */
    private static final Map<String, ChunkTicket> CHUNK_TICKETS = new ConcurrentHashMap<>();

    private record ChunkTicket(String world, int chunkX, int chunkZ) {
    }

    /**
     * Reservation clock for staggering first-time tracker creation - see
     * {@link #reserveFirstTimeCreateDelayMs()}.
     */
    private static final AtomicLong LAST_FIRST_TIME_CREATE_MS = new AtomicLong();
    private static final long FIRST_TIME_CREATE_STAGGER_MS = 150;

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
                spawnForPlayer(registry, npc, player);
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

        // Creating a brand-new tracker - its hitbox, its native Interaction, and one display
        // entity per model bone - is heavy enough, packet-wise, that doing it for a dozen-plus
        // NPCs within the same tick can flood a single client hard enough to disconnect it
        // outright. That exact burst happens whenever a player logs in and FancyNpcs applies
        // attributes for every NPC newly visible to them in one pass - confirmed live: "Too many
        // suspicious packets", plus a client-side entity-data type-mismatch crash from an entity
        // id being reused too quickly under that load. This must never be fixed by delaying
        // *whether* a tracker gets created (that already requires a real viewer - see
        // reconcileVisibility's own javadoc for why that's non-negotiable), only *when* - so this
        // reserves the next available slot at least FIRST_TIME_CREATE_STAGGER_MS after the last
        // first-time creation anywhere on the server, spreading a cluster of them out instead of
        // firing them all in the same tick. An isolated creation (nothing else recent) reserves
        // slot 0, i.e. still runs immediately - staggering only kicks in once creations are
        // actually clustered.
        long delayMs = reserveFirstTimeCreateDelayMs();
        if (delayMs <= 0) {
            createTrackerAndDispatch(npc, npcName, modelName, bukkitEntity);
        } else {
            Bukkit.getRegionScheduler().runDelayed(FancyNpcsModelPlugin.get(), npc.getData().getLocation(),
                    task -> createTrackerAndDispatch(npc, npcName, modelName, bukkitEntity),
                    Math.max(1, delayMs / 50));
        }
    }

    /**
     * Reserves the next available "slot" for a first-time tracker creation - a classic atomic
     * timestamp-reservation rate limiter. Concurrent/rapid calls get pushed out to at least
     * {@link #FIRST_TIME_CREATE_STAGGER_MS} apart; an isolated call (nothing reserved recently)
     * gets a delay of 0 or less, i.e. runs immediately.
     */
    private static long reserveFirstTimeCreateDelayMs() {
        long now = System.currentTimeMillis();
        long reserved = LAST_FIRST_TIME_CREATE_MS.updateAndGet(prev -> Math.max(now, prev + FIRST_TIME_CREATE_STAGGER_MS));
        return reserved - now;
    }

    /**
     * The actual heavy lifting of creating a brand-new tracker and dispatching its spawn packets -
     * split out from {@link #setModelOnEntityThread} purely so that call can be staggered (see
     * {@link #reserveFirstTimeCreateDelayMs()}) without duplicating this logic.
     */
    private static void createTrackerAndDispatch(Npc npc, String npcName, String modelName, Entity bukkitEntity) {
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
            spawnForPlayer(registry, npc, player);
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
                    // Silently returning here used to be indistinguishable from "not one of our
                    // NPCs" (the common, expected case - BetterModel is also used for non-NPC
                    // entities). Logged at warn, not debug: if this ever fires for a tracker that
                    // *does* belong to one of our NPCs (e.g. a transient lookup failure under
                    // server-start load), the tracker is left with no hitbox listeners and nothing
                    // else will log anything - this is the only place that could ever surface it.
                    FancyNpcsModelPlugin.get().getFancyLogger().debug(
                            "registerTrackerCreationListener: no matching NPC found for tracker sourceEntity="
                                    + tracker.sourceEntity().uuid() + " model=" + tracker.name()
                                    + " - leaving unconfigured for setModelOnEntityThread's own fallback/self-heal to retry"
                    );
                    return;
                }

                // Usually this fires synchronously inside our own getOrCreate() call in
                // setModelOnEntityThread, which is already dispatched onto the correct Folia region
                // thread for the npc's location (see that method's javadoc) - configureTracker's
                // createHitBox() call, which adds a real NMS entity to the world, is then safe to
                // run inline, right here.
                //
                // But BetterModel can also (re)create trackers entirely on its own, completely
                // outside that call stack - most notably /bettermodel reload, which rebuilds every
                // tracker from a plain async scheduler thread with no region ownership at all.
                // Calling createHitBox() directly from there throws Folia's AsyncCatcher
                // ("Asynchronous entity add!"), the hitbox never gets created, and nothing else
                // ever retries it - every model NPC stays unclickable until a player-triggered
                // (and therefore correctly-dispatched) tracker rebuild happens to occur. Detect
                // that case and hop onto the NPC's owning region thread first, same as every other
                // NMS-touching operation in this class.
                Location location = npc.getData().getLocation();
                if (location == null) {
                    FancyNpcsModelPlugin.get().getFancyLogger().debug(
                            "registerTrackerCreationListener: npc " + npc.getData().getName()
                                    + " has no location, cannot verify/dispatch to its region thread - leaving unconfigured"
                    );
                    return;
                }
                if (Bukkit.isOwnedByCurrentRegion(location)) {
                    configureTracker(npc, tracker);
                } else {
                    Bukkit.getRegionScheduler().run(FancyNpcsModelPlugin.get(), location,
                            task -> configureTracker(npc, tracker));
                }
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
     * One-time setup for a freshly created tracker: applies the NPC's scale, wires up hitbox click
     * listeners, and - only once that has fully succeeded - registers it as configured (idempotency
     * guard used by {@link #setModelOnEntityThread} and by the self-heal in
     * {@link #reconcileVisibility}/{@link #forceResyncForPlayer}/{@link #ensureTracker}). Called
     * from exactly one of two places for any given tracker - whichever gets there first, see
     * {@link #registerTrackerCreationListener} - never both, so listeners are never registered
     * twice on the same tracker (which would double-fire interactions).
     * <p>
     * Marking the tracker configured is deliberately the LAST step, not the first: this runs during
     * the exact join/server-start burst that {@link #forceResyncForPlayer}'s javadoc already
     * documents as capable of silently dropping work, and a tracker that throws partway through
     * setup (leaving some hitbox listeners unregistered) but still ends up in
     * {@link #CONFIGURED_TRACKERS} would look "done" forever - no self-heal ever revisits a tracker
     * once it's in that set, so the NPC would stay unclickable until an admin closed/reopened its
     * tracker some other way (e.g. {@code /bettermodel reload}). With this ordering, a failed
     * attempt leaves the tracker unconfigured, so the very next self-heal pass (within seconds,
     * see {@link #ensureTracker}) closes it and tries again from scratch.
     */
    private static void configureTracker(Npc npc, EntityTracker tracker) {
        // Keep this NPC's chunk force-loaded for as long as it has a tracker - see
        // #ensureChunkTicket's javadoc for why the hitbox is otherwise not reliably clickable.
        ensureChunkTicket(npc);

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

        // BetterModel also creates this hitbox itself, automatically, shortly after tracker
        // construction - but via a task scheduled through the *entity's own* location
        // (EntityTracker's constructor -> entity.platform().task(...)), at the exact moment the
        // tracker gets built. For these NPCs specifically, that first-ever tracker construction
        // happens right at server start (see the "onlinePlayers=0" debug line above) - before any
        // player is anywhere near the NPC's world/region. On Folia, a region with nobody in it
        // yet is not necessarily "active", and BetterModel's one-shot scheduling attempt for it can
        // silently never run - nothing ever retries it, so the hitbox (and therefore every click)
        // is missing forever, even though the model itself renders fine once a player later joins
        // (that's triggered separately, per-player, well after join - see spawnForPlayer). Manually
        // running /bettermodel reload "fixes" this only because it rebuilds the tracker at a point
        // where a player is already online and the region is definitely active.
        //
        // Force it explicitly here instead, dispatched the same way every other NPC-related
        // operation in this class is (Bukkit.getRegionScheduler() keyed off the NPC's own
        // configured location, not the entity's live one - see setModel's javadoc for why that
        // distinction matters for these fake, never-added-to-the-world entities). This runs
        // regardless of whether BetterModel's own attempt already succeeded or ever will - bones
        // that already have a hitbox are left alone (see BetterModel's own HITBOX_REFRESH_PREDICATE
        // pattern), so this is safe to always run, not just as a fallback.
        //
        // BetterModel's own convention (bone named exactly "hitbox", or tagged with its "b_"/"ob_"
        // prefix) is tried first, matching what BetterModel itself would have created. Its third,
        // internal-only condition (mount/seat bones) isn't reachable from addon code, so as a
        // guaranteed catch-all - the model author may use neither convention, or use a name/tag
        // this addon can't replicate exactly - fall back to a hitbox covering every bone whenever
        // the named/tagged attempt matches nothing. A model NPC that ends up with a hitbox on every
        // bone instead of one precisely placed one is still fully clickable, which is what matters
        // here; it is not visually different since these hitboxes aren't rendered.
        boolean createdNamedHitbox = tracker.createHitBox(null, BonePredicate.name("hitbox").or(BonePredicate.tag(BoneTags.HITBOX)).notSet());
        int hitboxCountAfterNamed = tracker.registry().hitBoxes().size();
        boolean createdFallbackHitbox = false;
        if (hitboxCountAfterNamed == 0) {
            createdFallbackHitbox = tracker.createHitBox(null, BonePredicate.TRUE);
        }

        CONFIGURED_TRACKERS.add(tracker);
        tracker.handleCloseEvent((closedTracker, reason) -> CONFIGURED_TRACKERS.remove(closedTracker));

        FancyNpcsModelPlugin.get().getFancyLogger().debug(
                "configureTracker completed for npc=" + npc.getData().getName() + " model=" + tracker.name()
                        + " createdNamedHitbox=" + createdNamedHitbox + " hitboxCountAfterNamed=" + hitboxCountAfterNamed
                        + " createdFallbackHitbox=" + createdFallbackHitbox
                        + " finalHitboxCount=" + tracker.registry().hitBoxes().size()
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
    private static void spawnForPlayer(EntityTrackerRegistry registry, Npc npc, Player player) {
        player.getScheduler().run(FancyNpcsModelPlugin.get(), task -> {
            // Logged unconditionally (not just at debug level) because a false return here, with no
            // exception and no other symptom, is exactly what a silently-dropped/never-rendered
            // model looks like from the server's side - see forceResyncForPlayer's javadoc. Without
            // this there is no way to tell "BetterModel declined to send anything" apart from "sent
            // fine, client just didn't render it" after the fact.
            if (!registry.spawn(BukkitAdapter.adapt(player))) {
                FancyNpcsModelPlugin.get().getFancyLogger().warn(
                        "registry.spawn() declined for npc " + npc.getData().getName() + " and player " + player.getName()
                                + " - BetterModel sent nothing (no tracker matched, or player not yet known to BetterModel)"
                );
            }
        }, null);
    }

    /**
     * Counterpart to {@link #spawnForPlayer}: tells BetterModel a player is no longer viewing an
     * NPC's model. Dispatched through the same per-player scheduler for the same reason
     * spawnForPlayer is - see its javadoc.
     */
    private static void despawnForPlayer(EntityTrackerRegistry registry, Player player) {
        player.getScheduler().run(FancyNpcsModelPlugin.get(), task -> registry.remove(BukkitAdapter.adapt(player)), null);
    }

    /**
     * Brings one NPC's model in sync with FancyNpcs' own visibility for one player: spawns it if
     * it should be shown and isn't (or, with {@code force}, even if BetterModel's own bookkeeping
     * already believes it's shown - see {@link #forceResyncForPlayer}), and despawns it if it
     * shouldn't be shown but still is.
     */
    private static void syncModelForPlayer(EntityTrackerRegistry registry, Npc npc, Player player, boolean force) {
        boolean shouldBeShown = npc.isShownFor(player);
        boolean isShown = registry.isSpawned(BukkitAdapter.adapt(player));

        if (shouldBeShown) {
            if ((force || !isShown) && !isResourcePackPending(player.getUniqueId())) {
                spawnForPlayer(registry, npc, player);
            }
        } else if (isShown) {
            despawnForPlayer(registry, player);
        }
    }

    /**
     * Periodic safety net that reconciles every model NPC's per-player BetterModel visibility
     * with FancyNpcs' own per-player visibility state ({@link Npc#isShownFor(Player)}), in both
     * directions - spawning the model for anyone who should see it but doesn't, and despawning it
     * for anyone who shouldn't see it anymore but still does.
     * <p>
     * This exists because these NPCs are packet-only fake entities that are never added to the
     * world (see {@link #getBukkitEntity}'s javadoc), so BetterModel's own automatic per-chunk
     * viewer tracking ({@code Entity#trackedBy()}) never sees them - nothing keeps a model's
     * visibility in sync with the base NPC's on its own, in either direction. The event-driven
     * path ({@code NpcSpawnListener} + {@link #setModelOnEntityThread}) handles the common case,
     * but only runs off FancyNpcs' own {@code NpcSpawnEvent}, which is fired from inside
     * {@code Npc#spawn(Player)} - itself normally triggered by Bukkit's
     * {@code PlayerChangedWorldEvent}/{@code PlayerTeleportEvent}. Both of those are known to not
     * reliably fire on Folia - and CanvasMC, a Folia fork, inherits the same gap by design (kept
     * for upstream Folia compatibility, see https://docs.canvasmc.io/canvas/info/folia/fixes/).
     * When that happens, the base NPC still (re)appears/disappears correctly, because FancyNpcs
     * separately self-heals visibility from its own periodic {@code VisibilityTracker}, which
     * doesn't depend on any Bukkit event - but nothing on this plugin's side ever re-ran, so the
     * model can be left permanently out of sync with it (most commonly: missing right after a
     * world change, or still shown server-side to a player who is no longer near/in the same
     * world as the NPC). Running this on its own timer, independent of any Bukkit event, closes
     * that gap the same way FancyNpcs' own tracker closes it for the base NPC.
     */
    public static void reconcileVisibility() {
        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            // This runs unattended on a timer, forever - a single bad NPC (no location, mid-removal,
            // whatever) throwing here must never be allowed to propagate: Bukkit.getAsyncScheduler()
            // is backed by a plain fixed-rate scheduler, and those silently stop rescheduling a task
            // for good after its first uncaught exception. That would look exactly like "some NPCs
            // never get their model back" - permanently, for the rest of the server's uptime, not
            // just a one-off skip - so every NPC gets its own try/catch instead of one around the
            // whole loop.
            try {
                if (!hasAttribute(npc) || npc.getData().getLocation() == null) continue;

                Bukkit.getRegionScheduler().run(FancyNpcsModelPlugin.get(), npc.getData().getLocation(), task -> {
                    try {
                        EntityTracker tracker = getEntityTracker(npc);
                        if (tracker == null || tracker.isClosed() || !CONFIGURED_TRACKERS.contains(tracker)) {
                            // Only (re)create a tracker if some online player should actually be
                            // seeing this NPC right now. Confirmed live, twice: creating one for an
                            // NPC nobody is near - including at server start, with zero players
                            // online at all, which this loop would otherwise hit on its very first
                            // tick - produces a hitbox that is never properly wired up for
                            // client-side interaction, even though the entity is added to the world
                            // without error and even with #ensureChunkTicket keeping its chunk
                            // permanently loaded so it can't be silently discarded afterwards. A real
                            // player being present at the moment of creation is a genuine
                            // requirement, not just a proxy for "region is loaded" - so this check
                            // must stay even though it means the very first player to see a given NPC
                            // this server run pays the cost of first-time creation (see
                            // forceResyncForPlayer's javadoc for the separate, smaller join-burst
                            // packet-volume issue that can cause).
                            boolean hasViewer = Bukkit.getOnlinePlayers().stream().anyMatch(npc::isShownFor);
                            if (!hasViewer) {
                                return;
                            }

                            Entity bukkitEntity = getBukkitEntity(npc);
                            if (bukkitEntity != null) {
                                ensureTracker(npc, npc.getData().getName(), bukkitEntity);
                            }
                            return;
                        }

                        EntityTrackerRegistry registry = tracker.registry();
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            syncModelForPlayer(registry, npc, player, false);
                        }
                    } catch (Throwable t) {
                        FancyNpcsModelPlugin.get().getFancyLogger().error(
                                "Failed to reconcile model visibility for npc " + npc.getData().getName(),
                                ThrowableProperty.of(t)
                        );
                    }
                });
            } catch (Throwable t) {
                FancyNpcsModelPlugin.get().getFancyLogger().error(
                        "Failed to schedule model visibility reconciliation for npc " + npc.getData().getName(),
                        ThrowableProperty.of(t)
                );
            }
        }
    }

    /**
     * One-shot, unconditional model resend for a single player - unlike {@link #reconcileVisibility},
     * this ignores {@code registry.isSpawned(player)} and resends regardless.
     * <p>
     * Needed for a join-time race this plugin's own debug logs confirmed: on a fresh join, every
     * visible NPC has its model attribute applied within the same second (one
     * {@code checkAndUpdateVisibility} pass per NPC), which for any NPC nobody has looked at yet
     * this server run means creating its {@code EntityTracker} for the first time - the "create new
     * tracker" branch in {@link #setModelOnEntityThread}. With a dozen-plus NPCs clustered at a
     * spawn hub all doing that within the same tick, some of their spawn dispatches land fine and
     * some don't: {@code registry.spawn()} still returns normally and marks the player as spawned in
     * BetterModel's own bookkeeping, but the model never actually renders client-side for a subset
     * of them (same root cause the existing "unconditional spawn(), NOT spawnIfNotSpawned()" comment
     * on {@link #spawnForPlayer}'s caller already describes: server-side "isSpawned" can be true
     * even when the client never rendered it). Because the server-side state already says "spawned",
     * {@link #reconcileVisibility}'s isShown check can't detect or fix this - it needs an
     * unconditional resend instead. A world change fixes it by accident, because it forces every NPC
     * (and its model) through a full remove+respawn one at a time, well outside that initial burst;
     * this does the equivalent for a fresh join.
     * <p>
     * Staggered ~150ms apart per NPC instead of firing all of them in the same instant: doing that
     * would just reproduce the exact same "many mount packets for the same player in one tick"
     * pattern the join burst already causes trouble with (confirmed live: an earlier version of this
     * method fired every NPC in the same tick and the exact same class of failure still occurred, on
     * a different pair of NPCs each time - clearly a volume/timing issue, not one tied to any
     * specific NPC).
     */
    public static void forceResyncForPlayer(Player player) {
        int index = 0;
        for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
            if (!hasAttribute(npc) || npc.getData().getLocation() == null) continue;

            long delayMs = 150L * index++;
            Bukkit.getAsyncScheduler().runDelayed(FancyNpcsModelPlugin.get(), (delayedTask) -> {
                try {
                    if (!player.isOnline()) return;

                    Bukkit.getRegionScheduler().run(FancyNpcsModelPlugin.get(), npc.getData().getLocation(), task -> {
                        try {
                            EntityTracker tracker = getEntityTracker(npc);
                            if (tracker == null || tracker.isClosed() || !CONFIGURED_TRACKERS.contains(tracker)) {
                                // Same reasoning as reconcileVisibility's own viewer check: only
                                // (re)create a tracker for this NPC if the joining player should
                                // actually see it. Without this, every join force-created a tracker
                                // for every model NPC on the server - including ones nowhere near
                                // this player, in worlds/regions with no real viewer at all - which
                                // hits the same "hitbox added but never properly tracked" failure
                                // this method exists to work around in the first place.
                                if (!npc.isShownFor(player)) {
                                    return;
                                }

                                Entity bukkitEntity = getBukkitEntity(npc);
                                if (bukkitEntity != null) {
                                    ensureTracker(npc, npc.getData().getName(), bukkitEntity);
                                }
                                return;
                            }

                            syncModelForPlayer(tracker.registry(), npc, player, true);
                        } catch (Throwable t) {
                            FancyNpcsModelPlugin.get().getFancyLogger().error(
                                    "Failed to force-resync model for npc " + npc.getData().getName() + " and player " + player.getName(),
                                    ThrowableProperty.of(t)
                            );
                        }
                    });
                } catch (Throwable t) {
                    FancyNpcsModelPlugin.get().getFancyLogger().error(
                            "Failed to schedule forced model resync for npc " + npc.getData().getName(),
                            ThrowableProperty.of(t)
                    );
                }
            }, Math.max(delayMs, 1L), TimeUnit.MILLISECONDS);
        }
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

                // The player's pack was still loading when this NPC last checked visibility, so it
                // may have moved on (e.g. changed world again) before the pack finished - only
                // catch it up on NPCs FancyNpcs actually still considers visible to them, otherwise
                // this would spawn a model with no base NPC behind it for the player to see.
                if (!npc.isShownFor(player)) return;

                spawnForPlayer(tracker.registry(), npc, player);
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
     * <p>
     * Exception: while the plugin is disabled (i.e. during {@code onDisable}), the region scheduler
     * refuses to accept new tasks at all - Bukkit flips {@code isEnabled} to false before calling
     * {@code onDisable}, and Folia enforces that no plugin may schedule anything once disabled,
     * throwing {@link org.bukkit.plugin.IllegalPluginAccessException}. In that case close directly
     * on the calling thread instead of dispatching, since by then all regions are shutting down
     * anyway.
     */
    public static void closeAllTrackers(Npc npc) {
        LAST_TRACKER_RECREATE_ATTEMPT.remove(npc.getData().getId());
        releaseChunkTicket(npc);

        Entity bukkitEntity = getBukkitEntity(npc);
        if (bukkitEntity == null) {
            return;
        }

        if (!FancyNpcsModelPlugin.get().isEnabled()) {
            closeAllTrackers(bukkitEntity);
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
     * Keeps the chunk containing this NPC force-loaded (and therefore actively ticking, on Folia)
     * for as long as it has a configured tracker, via a plugin chunk ticket.
     * <p>
     * BetterModel's hitbox - and the native {@code Interaction} entity it mounts for click
     * detection - are real, non-persistent NMS entities (explicitly {@code persist = false} in
     * BetterModel's own {@code HitBoxImpl}), added to the world exactly once, right when
     * {@link #configureTracker} runs. Nothing else keeps their chunk loaded afterwards: these NPCs
     * are themselves packet-only fake entities never added to the world (see
     * {@link #getBukkitEntity}), so unlike a normal entity there's no vanilla mechanism tying the
     * chunk's lifetime to anything visible. Without a ticket, that chunk is free to unload (or, on
     * Folia, may never have had a genuinely active/ticking owning region in the first place) the
     * moment nothing else needs it - which is exactly what happens right after server start, when
     * trackers get created with zero players anywhere near. Once that happens the hitbox and its
     * interaction entity are gone for good (non-persistent means they're never saved/reloaded from
     * disk), while both BetterModel's and this plugin's own bookkeeping still believe the tracker
     * is fully configured (see {@link #CONFIGURED_TRACKERS}/{@link #hasActiveHitbox}) - the NPC
     * silently becomes permanently unclickable with no error anywhere. The only previously-known
     * fix was rebuilding the tracker later while a player happened to be genuinely nearby (e.g.
     * via {@code /bettermodel reload}). Forcing the chunk to stay loaded from the moment the
     * hitbox is created removes the failure mode entirely instead of trying to detect/retry it
     * after the fact.
     */
    private static void ensureChunkTicket(Npc npc) {
        Location location = npc.getData().getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        ChunkTicket ticket = new ChunkTicket(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        ChunkTicket previous = CHUNK_TICKETS.put(npc.getData().getId(), ticket);
        if (ticket.equals(previous)) {
            return;
        }
        if (previous != null) {
            releaseChunkTicket(previous);
        }

        location.getWorld().addPluginChunkTicket(ticket.chunkX(), ticket.chunkZ(), FancyNpcsModelPlugin.get());
    }

    /**
     * Releases this NPC's chunk ticket added by {@link #ensureChunkTicket}, if any. Called
     * whenever this NPC's trackers are closed for good (removal, plugin disable, model reset via
     * command) - never from the model-switch path in {@link #setModelOnEntityThread}, which closes
     * the old tracker only to immediately create a new one at the same location.
     */
    private static void releaseChunkTicket(Npc npc) {
        ChunkTicket ticket = CHUNK_TICKETS.remove(npc.getData().getId());
        if (ticket != null) {
            releaseChunkTicket(ticket);
        }
    }

    private static void releaseChunkTicket(ChunkTicket ticket) {
        World world = Bukkit.getWorld(ticket.world());
        if (world != null) {
            world.removePluginChunkTicket(ticket.chunkX(), ticket.chunkZ(), FancyNpcsModelPlugin.get());
        }
    }

    /**
     * Whether the given NPC currently has at least one clickable BetterModel hitbox. Used by
     * {@link com.fancyinnovations.fancynpcsmodel.listeners.NpcInteractListener} to decide whether
     * it's safe to cancel FancyNpcs' own base click.
     * <p>
     * BetterModel only ever creates a {@code HitBox} for bones that opt in - named exactly
     * {@code hitbox}, tagged with {@code BoneTags.HITBOX} (i.e. a {@code b_}/{@code ob_} name
     * prefix), or seat/mount bones (see {@code EntityTracker#CREATE_HITBOX_PREDICATE} in
     * BetterModel's own source). A {@code .bbmodel} with none of those bones gets a tracker (the
     * model renders fine) but never gets a single {@code HitBox} - so {@link #configureTracker}'s
     * {@code listenHitBox} calls have nothing to ever fire, silently, with no error anywhere. Also
     * covers the (much shorter-lived) window right after tracker creation, before BetterModel's own
     * async initial {@code createHitBox} call has run yet.
     */
    public static boolean hasActiveHitbox(Npc npc) {
        EntityTracker tracker = getEntityTracker(npc);
        if (tracker == null || tracker.isClosed() || !CONFIGURED_TRACKERS.contains(tracker)) {
            return false;
        }

        return !tracker.registry().hitBoxes().isEmpty();
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

    /**
     * Self-heal for an NPC whose tracker is missing entirely (never created, closed with nothing
     * rebuilding it, or created but never configured with hitbox listeners). Called from
     * {@link #reconcileVisibility} and {@link #forceResyncForPlayer} when they find no usable
     * tracker to sync - previously they just returned in that case, which is correct if the
     * tracker merely hasn't been created *yet* on this call (e.g. mid-spawn), but permanently
     * strands any NPC whose very first tracker creation attempt failed outright (e.g.
     * {@link #getBukkitEntity} transiently returning null under the load of a server-start join
     * burst, or the {@code CreateEntityTrackerEvent} subscription throwing before attaching
     * listeners). Nothing else ever retries creation - not the periodic reconcile, not a world
     * change, not a reconnect - so without this an affected NPC's model/hitbox stayed gone for
     * good until an admin ran {@code /bettermodel reload}, and since {@link
     * com.fancyinnovations.fancynpcsmodel.listeners.NpcInteractListener} unconditionally cancels
     * the base click for any NPC with this attribute, that also meant the NPC was permanently
     * unclickable.
     * <p>
     * Re-drives the exact same creation path {@link #setModel} itself uses, rate-limited per NPC
     * so a genuinely misconfigured model (bad model name) doesn't retry - and error-log - every
     * second forever.
     */
    private static void ensureTracker(Npc npc, String npcName, Entity bukkitEntity) {
        String id = npc.getData().getId();
        long now = System.currentTimeMillis();
        Long last = LAST_TRACKER_RECREATE_ATTEMPT.get(id);
        if (last != null && now - last < TRACKER_RECREATE_INTERVAL_MS) {
            return;
        }
        LAST_TRACKER_RECREATE_ATTEMPT.put(id, now);

        String modelName = getConfiguredModelName(npc);
        if (modelName == null) {
            return;
        }

        setModelOnEntityThread(npc, npcName, modelName, bukkitEntity);
    }

    private static String getConfiguredModelName(Npc npc) {
        for (Map.Entry<NpcAttribute, String> entry : npc.getData().getAttributes().entrySet()) {
            if (entry.getKey().getName().equalsIgnoreCase(ATTRIBUTE_NAME)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
