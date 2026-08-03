package com.onthehill.chunkyfriends.chunky;

import java.util.function.Consumer;

import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.api.ChunkyAPI;
import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;
import org.popcraft.chunky.iterator.PatternType;
import org.popcraft.chunky.shape.ShapeType;

/**
 * Thin, directly-typed wrapper around Chunky's {@link ChunkyAPI}.
 *
 * @implNote {@code isRunning(world)} returning {@code false} and {@link GenerationCompleteEvent} firing both
 *     happen identically whether a task was paused, cancelled, or genuinely finished — neither one
 *     distinguishes the three. Only {@link GenerationProgressEvent#complete()} does, because it is only ever
 *     {@code true} when the task's chunk iterator is actually exhausted. Every completion check built on top
 *     of this class must key off that flag, never off task-map absence alone.
 */
public final class ChunkyGateway
{
    /**
     * Number of blocks per chunk edge, used to convert this mod's chunk-radius values into the block-radius
     * values Chunky's own API actually expects.
     *
     * @implNote {@code ChunkyAPI.startTask}'s {@code radiusX}/{@code radiusZ} parameters are in
     *     <strong>blocks</strong>, not chunks, despite there being no indication of that in the API's own
     *     Javadoc. Confirmed directly from Chunky's source: {@code Selection}'s constructor computes
     *     {@code radiusChunksX = (int) Math.ceil(radiusX / 16f)} — i.e. it divides the raw parameter by 16 to
     *     get a chunk count — and Chunky's own {@code /chunky radius}/{@code /chunky start} commands parse
     *     user input via {@code Input.tryDoubleSuffixed}, where a bare number has no multiplier and a
     *     trailing {@code 'c'} multiplies by 16 specifically to convert a chunk count into blocks. Every
     *     value this class sends to {@link ChunkyAPI#startTask} must be multiplied by this constant first.
     */
    private static final double BLOCKS_PER_CHUNK = 16.0;

    private ChunkyAPI _api;

    /**
     * Resolves and stores the active {@link ChunkyAPI} instance. Must be called after Chunky's own
     * {@code ServerLifecycleEvents.SERVER_STARTED} listener has run, since {@code ChunkyProvider} is only
     * populated at that point.
     */
    public void init()
    {
        _api = ChunkyProvider.get().getApi();
    }

    /**
     * Gets whether a generation task is currently running for a world.
     *
     * @param world The world identifier, e.g. {@code "minecraft:overworld"}.
     * @return If a task is running in that world.
     */
    public boolean isRunning(final String world)
    {
        return _api.isRunning(world);
    }

    /**
     * Starts a full-disk circular generation task centered on the given coordinates.
     *
     * @param world The world identifier, e.g. {@code "minecraft:overworld"}.
     * @param centerX The center x coordinate.
     * @param centerZ The center z coordinate.
     * @param radiusChunks The radius, in chunks, of the disk to generate.
     * @return If the task was created and started successfully.
     * @implNote Always requests the <em>full</em> disk out to {@code radiusChunks}, never an incremental ring
     *     between the previous and new radius. This is deliberate, not an oversight: Chunky's own generation
     *     task checks an in-memory per-region bitset before touching any chunk, so re-requesting ground it
     *     already covered costs it almost nothing — it skips straight through and only does real work on the
     *     genuinely new area. A full-disk request is simpler to reason about than tracking annuli, and this
     *     mod's own bookkeeping of "how much is covered" (ring tiers) never has to be exactly right for
     *     correctness — Chunky's on-disk chunk state is the actual source of truth, and is immune to this
     *     mod resetting/recomputing its own tier tracking for any reason (config changes, a player moving
     *     between service cycles, etc.). Worst case a "wasted" full-disk request is nearly free; there is no
     *     scenario where it causes anything to be regenerated that Chunky already has.
     */
    public boolean startTask(final String world, final double centerX, final double centerZ, final double radiusChunks)
    {
        final double radiusBlocks = radiusChunks * BLOCKS_PER_CHUNK;
        return _api.startTask(world, ShapeType.CIRCLE, centerX, centerZ, radiusBlocks, radiusBlocks, PatternType.CONCENTRIC);
    }

    /**
     * Pauses a generation task in a world.
     *
     * @param world The world identifier.
     * @return If the task was paused.
     */
    public boolean pauseTask(final String world)
    {
        return _api.pauseTask(world);
    }

    /**
     * Continues a paused generation task in a world, resuming Chunky's own saved progress.
     *
     * @param world The world identifier.
     * @return If the task was continued.
     */
    public boolean continueTask(final String world)
    {
        return _api.continueTask(world);
    }

    /**
     * Registers a listener invoked whenever a generation task calculates progress.
     *
     * @param listener The listener to register.
     */
    public void onGenerationProgress(final Consumer<GenerationProgressEvent> listener)
    {
        _api.onGenerationProgress(listener);
    }

    /**
     * Registers a listener invoked whenever a generation task disappears from Chunky's running-task set,
     * for any reason (pause, cancel, or genuine completion).
     *
     * @param listener The listener to register.
     */
    public void onGenerationComplete(final Consumer<GenerationCompleteEvent> listener)
    {
        _api.onGenerationComplete(listener);
    }
}
