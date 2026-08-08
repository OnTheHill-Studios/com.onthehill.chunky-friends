package com.onthehill.chunkyfriends.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.platform.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onthehill.chunkyfriends.network.MapPreviewRequestPayload;
import com.onthehill.chunkyfriends.network.MapPreviewResponsePayload;

/**
 * Server-side sampler streaming a terrain color grid around the requesting player's current position for the
 * config screen's ring preview background — real, already-generated terrain only, never triggering new world
 * generation.
 *
 * @implNote Centered on the requesting player's own current position (in whatever dimension they're currently
 *     in), not world spawn — this preview exists to show what {@code PregenScheduler} would actually do for
 *     that player, and {@code PregenScheduler} centers every ring job on the player's own last-known position
 *     (see {@code selectAndStartNext}), never on spawn.
 * @implNote There is no publicly-reachable API (short of reflecting into {@code ChunkMap}'s private region-file
 *     storage, which this class deliberately avoids) to read a column's surface block/height without the
 *     containing chunk being resident in the live chunk system — vanilla's own non-generating chunk access
 *     only serves already-loaded chunks, so a chunk Chunky confirms exists still has to be explicitly loaded
 *     (see {@link World#getChunkAtAsync(int, int)}) before it can be read, even though only a single column
 *     per chunk is actually needed for this preview. Rather than force-loading everything a large radius
 *     touches in one burst (a real, observed disconnect/crash risk at tens of thousands of chunks) or hard-
 *     capping and permanently fogging whatever didn't make the cut, sampling is streamed in fixed-size,
 *     nearest-chunk-first batches — see {@link #sampleAsync} — so every chunk eventually gets sampled, the
 *     areas nearest the player resolve first and visibly fastest, and no single burst is larger than
 *     {@value #CHUNK_BATCH_SIZE} chunks.
 */
public final class TerrainPreviewSampler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainPreviewSampler.class);

    /**
     * Width and height, in pixels, of the sampled preview image.
     */
    public static final int MAP_PREVIEW_IMAGE_DIMENSION_PIXELS = 256;

    /**
     * Ceiling radius, in chunks, past which the preview is explicitly allowed to stop scaling further.
     */
    public static final int MAP_PREVIEW_MAX_RADIUS_CHUNKS = 1000;

    private static final int BLOCKS_PER_CHUNK = 16;

    /**
     * Number of distinct chunks force-loaded and read per streamed batch. Chosen to keep any single burst of
     * work modest regardless of the total — a preview touching tens of thousands of distinct chunks (worst
     * case: a ~128-chunk radius, where {@code blocksPerPixel} is close enough to 16 that nearly every pixel
     * lands in its own distinct chunk) is simply split into that many more batches, streamed one after another,
     * rather than attempted all at once.
     */
    private static final int CHUNK_BATCH_SIZE = 400;

    private TerrainPreviewSampler()
    {
    }

    /**
     * Samples a terrain color grid around the requesting player's current position, streaming results back as
     * they become available.
     *
     * @param player The requesting player; sampling is centered on their current position, in their current
     *     dimension.
     * @param request The requesting client's currently-entered preview radius and request identifier.
     * @param onUpdate Invoked once per completed batch — one or more times, always on the server's main thread
     *     (safe to touch {@link ServerLevel}/network state from directly) — with a full snapshot of everything
     *     resolved so far. The final invocation has {@link MapPreviewResponsePayload#isFinalUpdate()} set.
     */
    public static void sampleAsync(final ServerPlayer player, final MapPreviewRequestPayload request, final Consumer<MapPreviewResponsePayload> onUpdate)
    {
        final ServerLevel level = player.level();
        final MinecraftServer server = level.getServer();
        final int originBlockX = (int) Math.floor(player.getX());
        final int originBlockZ = (int) Math.floor(player.getZ());
        final int requestId = request.requestId();

        final boolean clamped = request.requestedRadiusChunks() > MAP_PREVIEW_MAX_RADIUS_CHUNKS;
        final int coverageRadiusChunks = Math.min(Math.max(request.requestedRadiusChunks(), 0), MAP_PREVIEW_MAX_RADIUS_CHUNKS);
        final double blocksPerPixel = (2.0 * coverageRadiusChunks * BLOCKS_PER_CHUNK) / MAP_PREVIEW_IMAGE_DIMENSION_PIXELS;

        if (blocksPerPixel <= 0)
        {
            onUpdate.accept(emptyResponse(originBlockX, originBlockZ, blocksPerPixel, clamped, requestId));
            return;
        }

        final String worldKey = level.dimension().identifier().toString();
        final World chunkyWorld = ChunkyProvider.get().getServer().getWorld(worldKey).orElse(null);
        if (chunkyWorld == null)
        {
            LOGGER.warn("Map preview: Chunky has no world registered for {} — rendering an all-fog preview.", worldKey);
            onUpdate.accept(emptyResponse(originBlockX, originBlockZ, blocksPerPixel, clamped, requestId));
            return;
        }

        final int center = MAP_PREVIEW_IMAGE_DIMENSION_PIXELS / 2;
        final int[] pixelChunkX = new int[MAP_PREVIEW_IMAGE_DIMENSION_PIXELS * MAP_PREVIEW_IMAGE_DIMENSION_PIXELS];
        final int[] pixelChunkZ = new int[pixelChunkX.length];
        final Map<Long, List<Integer>> pixelIndicesByChunk = new HashMap<>();
        for (int pz = 0; pz < MAP_PREVIEW_IMAGE_DIMENSION_PIXELS; pz++)
        {
            for (int px = 0; px < MAP_PREVIEW_IMAGE_DIMENSION_PIXELS; px++)
            {
                final int index = pz * MAP_PREVIEW_IMAGE_DIMENSION_PIXELS + px;
                final int worldBlockX = originBlockX + (int) Math.round((px - center) * blocksPerPixel);
                final int worldBlockZ = originBlockZ + (int) Math.round((pz - center) * blocksPerPixel);
                final int chunkX = worldBlockX >> 4;
                final int chunkZ = worldBlockZ >> 4;
                final long key = chunkKey(chunkX, chunkZ);
                pixelChunkX[index] = chunkX;
                pixelChunkZ[index] = chunkZ;
                pixelIndicesByChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
            }
        }

        // Nearest-to-player first, so the earliest (fastest-visible) batches always cover the area right
        // around the player, regardless of how many batches the full radius ultimately takes.
        final int originChunkX = originBlockX >> 4;
        final int originChunkZ = originBlockZ >> 4;
        final List<Long> chunksByDistance = new ArrayList<>(pixelIndicesByChunk.keySet());
        chunksByDistance.sort(Comparator.comparingLong(key ->
        {
            final long dx = chunkX(key) - originChunkX;
            final long dz = chunkZ(key) - originChunkZ;
            return dx * dx + dz * dz;
        }));

        final Map<Long, CompletableFuture<Boolean>> generatedFutures = new HashMap<>();
        for (final long key : chunksByDistance)
        {
            generatedFutures.put(key, chunkyWorld.isChunkGenerated(chunkX(key), chunkZ(key)));
        }

        LOGGER.info("Sampling map preview around {} (request {}): {} distinct chunks across {} batch(es).",
                worldKey, requestId, chunksByDistance.size(), (chunksByDistance.size() + CHUNK_BATCH_SIZE - 1) / CHUNK_BATCH_SIZE);

        final byte[] colorGrid = new byte[pixelChunkX.length];
        processBatch(0, chunksByDistance, generatedFutures, pixelIndicesByChunk, colorGrid, level, chunkyWorld, server,
                worldKey, originBlockX, originBlockZ, blocksPerPixel, clamped, requestId, onUpdate);
    }

    private static void processBatch(final int batchStart, final List<Long> chunksByDistance, final Map<Long, CompletableFuture<Boolean>> generatedFutures,
            final Map<Long, List<Integer>> pixelIndicesByChunk, final byte[] colorGrid, final ServerLevel level, final World chunkyWorld,
            final MinecraftServer server, final String worldKey, final int originBlockX, final int originBlockZ, final double blocksPerPixel,
            final boolean clamped, final int requestId, final Consumer<MapPreviewResponsePayload> onUpdate)
    {
        final int batchEnd = Math.min(batchStart + CHUNK_BATCH_SIZE, chunksByDistance.size());
        final List<Long> batch = chunksByDistance.subList(batchStart, batchEnd);

        final List<CompletableFuture<Boolean>> batchExistence = new ArrayList<>();
        for (final long key : batch)
        {
            batchExistence.add(generatedFutures.get(key));
        }

        CompletableFuture.allOf(batchExistence.toArray(new CompletableFuture[0]))
                .thenCompose(ignoredResult ->
                {
                    final List<Long> toLoad = new ArrayList<>();
                    final List<CompletableFuture<Void>> loadFutures = new ArrayList<>();
                    for (final long key : batch)
                    {
                        if (Boolean.TRUE.equals(generatedFutures.get(key).getNow(false)))
                        {
                            toLoad.add(key);
                            loadFutures.add(chunkyWorld.getChunkAtAsync(chunkX(key), chunkZ(key)));
                        }
                    }
                    return CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture[0])).thenApply(ignored2 -> toLoad);
                })
                .whenComplete((toLoad, throwable) ->
                {
                    final List<Long> loadedThisBatch;
                    if (throwable != null)
                    {
                        LOGGER.error("Map preview: batch load failed for {} (request {}).", worldKey, requestId, throwable);
                        loadedThisBatch = List.of();
                    }
                    else
                    {
                        loadedThisBatch = toLoad;
                    }
                    server.execute(() ->
                    {
                        for (final long key : loadedThisBatch)
                        {
                            sampleChunkPixels(level, key, pixelIndicesByChunk.getOrDefault(key, List.of()), originBlockX, originBlockZ, blocksPerPixel, colorGrid);
                        }
                        final boolean isFinal = batchEnd >= chunksByDistance.size();
                        onUpdate.accept(new MapPreviewResponsePayload(MAP_PREVIEW_IMAGE_DIMENSION_PIXELS, MAP_PREVIEW_IMAGE_DIMENSION_PIXELS,
                                blocksPerPixel, originBlockX, originBlockZ, clamped, colorGrid.clone(), requestId, isFinal));
                        if (isFinal)
                        {
                            LOGGER.info("Finished sampling map preview around {} (request {}).", worldKey, requestId);
                        }
                        else
                        {
                            processBatch(batchEnd, chunksByDistance, generatedFutures, pixelIndicesByChunk, colorGrid, level, chunkyWorld, server,
                                    worldKey, originBlockX, originBlockZ, blocksPerPixel, clamped, requestId, onUpdate);
                        }
                    });
                });
    }

    private static void sampleChunkPixels(final ServerLevel level, final long chunkKey, final List<Integer> pixelIndices,
            final int originBlockX, final int originBlockZ, final double blocksPerPixel, final byte[] colorGrid)
    {
        final int chunkX = chunkX(chunkKey);
        final int chunkZ = chunkZ(chunkKey);
        final int center = MAP_PREVIEW_IMAGE_DIMENSION_PIXELS / 2;
        for (final int pixelIndex : pixelIndices)
        {
            final int px = pixelIndex % MAP_PREVIEW_IMAGE_DIMENSION_PIXELS;
            final int pz = pixelIndex / MAP_PREVIEW_IMAGE_DIMENSION_PIXELS;
            final int worldBlockX = originBlockX + (int) Math.round((px - center) * blocksPerPixel);
            final int worldBlockZ = originBlockZ + (int) Math.round((pz - center) * blocksPerPixel);
            colorGrid[pixelIndex] = sampleColumn(level, chunkX, chunkZ, worldBlockX, worldBlockZ);
        }
    }

    private static MapPreviewResponsePayload emptyResponse(final int originBlockX, final int originBlockZ, final double blocksPerPixel,
            final boolean clamped, final int requestId)
    {
        return new MapPreviewResponsePayload(MAP_PREVIEW_IMAGE_DIMENSION_PIXELS, MAP_PREVIEW_IMAGE_DIMENSION_PIXELS,
                blocksPerPixel, originBlockX, originBlockZ, clamped, new byte[MAP_PREVIEW_IMAGE_DIMENSION_PIXELS * MAP_PREVIEW_IMAGE_DIMENSION_PIXELS],
                requestId, true);
    }

    /**
     * Samples a single column's map color. The caller is responsible for having already confirmed the
     * containing chunk exists (see {@link World#isChunkGenerated(int, int)}) and is loaded (see
     * {@link World#getChunkAtAsync(int, int)}) — this only reads it.
     *
     * @param level The level to sample.
     * @param chunkX Chunk x coordinate of the column, already confirmed to exist and be loaded.
     * @param chunkZ Chunk z coordinate of the column, already confirmed to exist and be loaded.
     * @param worldBlockX World block x coordinate of the column.
     * @param worldBlockZ World block z coordinate of the column.
     * @return The column's packed vanilla map color byte, or {@code 0} (the "ungenerated/fog" sentinel) if
     *     the chunk could not be read without generating it despite Chunky reporting it as generated (e.g. a
     *     transient discrepancy) — never generates to force a non-fog result.
     */
    private static byte sampleColumn(final ServerLevel level, final int chunkX, final int chunkZ, final int worldBlockX, final int worldBlockZ)
    {
        final ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null)
        {
            return 0;
        }
        final int localX = worldBlockX & 15;
        final int localZ = worldBlockZ & 15;
        final int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
        final BlockPos pos = new BlockPos(worldBlockX, surfaceY, worldBlockZ);
        final BlockState state = chunk.getBlockState(pos);
        final MapColor mapColor = state.getMapColor(chunk, pos);
        if (mapColor == MapColor.NONE)
        {
            return 0;
        }
        return mapColor.getPackedId(MapColor.Brightness.NORMAL);
    }

    private static long chunkKey(final int chunkX, final int chunkZ)
    {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(final long key)
    {
        return (int) (key >> 32);
    }

    private static int chunkZ(final long key)
    {
        return (int) key;
    }
}
