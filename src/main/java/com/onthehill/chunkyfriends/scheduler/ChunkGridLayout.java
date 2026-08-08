package com.onthehill.chunkyfriends.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure utility computing where real chunk-boundary grid lines fall in the config screen's ring preview panel,
 * subsampled to a coarser real-chunk multiple when a literal every-chunk grid would be sub-pixel.
 */
public final class ChunkGridLayout
{
    /**
     * Number of blocks per chunk edge.
     */
    private static final int BLOCKS_PER_CHUNK = 16;

    /**
     * Minimum on-screen spacing, in pixels, between adjacent chunk-grid lines before the grid steps up to a
     * coarser (but still accurate) multiple of the real chunk size.
     */
    private static final int MIN_GRID_LINE_SPACING_PIXELS = 4;

    private ChunkGridLayout()
    {
    }

    /**
     * Computes how many real chunks apart adjacent grid lines should be drawn, so lines stay legible instead
     * of aliasing into a solid wash once a single chunk covers a fraction of one screen pixel.
     *
     * @param blocksPerPixel Blocks of world space represented by one panel pixel; {@code <= 0} means no
     *     preview response has been received yet.
     * @return {@code 1} to draw every real chunk boundary, or a larger step to draw every Nth one. Always a
     *     sane, defined positive value, even for {@code blocksPerPixel <= 0}.
     */
    public static int computeGridStepChunks(final double blocksPerPixel)
    {
        if (blocksPerPixel <= 0)
        {
            return 1;
        }
        final double chunkPixelSize = BLOCKS_PER_CHUNK / blocksPerPixel;
        if (chunkPixelSize >= MIN_GRID_LINE_SPACING_PIXELS)
        {
            return 1;
        }
        return (int) Math.ceil(MIN_GRID_LINE_SPACING_PIXELS / chunkPixelSize);
    }

    /**
     * Computes the panel pixel offsets at which real chunk-boundary grid lines fall along one axis, generic
     * over x or z — called once per axis with that axis's own origin block coordinate.
     *
     * @param originBlock World block coordinate the panel's center pixel corresponds to, on this axis.
     * @param blocksPerPixel Blocks of world space represented by one panel pixel.
     * @param panelSizePixels Size of the panel along this axis, in pixels.
     * @param gridStepChunks How many real chunks apart adjacent lines are, from {@link #computeGridStepChunks}.
     * @return Every pixel offset within {@code [0, panelSizePixels)} at which a true chunk boundary spaced
     *     {@code gridStepChunks} apart falls. Aligned to real chunk boundaries in world space (multiples of
     *     {@value #BLOCKS_PER_CHUNK} blocks), not to the panel's own edge — {@code originBlock} need not
     *     itself be chunk-aligned. Empty if {@code blocksPerPixel <= 0} or {@code panelSizePixels <= 0}.
     */
    public static List<Integer> computeGridLinePixelPositions(final int originBlock, final double blocksPerPixel, final int panelSizePixels, final int gridStepChunks)
    {
        final List<Integer> positions = new ArrayList<>();
        if (blocksPerPixel <= 0 || panelSizePixels <= 0)
        {
            return positions;
        }
        final long stepBlocks = (long) Math.max(1, gridStepChunks) * BLOCKS_PER_CHUNK;
        final double panelLeftWorldBlock = originBlock - (panelSizePixels / 2.0) * blocksPerPixel;
        long boundaryBlock = Math.floorDiv((long) Math.floor(panelLeftWorldBlock), stepBlocks) * stepBlocks;
        while (true)
        {
            final double pixel = (boundaryBlock - originBlock) / blocksPerPixel + panelSizePixels / 2.0;
            final int pixelPos = (int) Math.round(pixel);
            if (pixelPos >= panelSizePixels)
            {
                break;
            }
            if (pixelPos >= 0)
            {
                positions.add(pixelPos);
            }
            boundaryBlock += stepBlocks;
        }
        return positions;
    }
}
