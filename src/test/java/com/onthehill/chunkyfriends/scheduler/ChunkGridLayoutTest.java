package com.onthehill.chunkyfriends.scheduler;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkGridLayoutTest
{
    private static final int MIN_GRID_LINE_SPACING_PIXELS = 4;

    @Test
    void computeGridStepChunks_smallBlocksPerPixel_returnsOne()
    {
        // Arrange — a small preview radius, where one chunk already spans several pixels
        final double blocksPerPixel = 1.0;

        // Act
        final int result = ChunkGridLayout.computeGridStepChunks(blocksPerPixel);

        // Assert
        assertEquals(1, result);
    }

    @Test
    void computeGridStepChunks_largeBlocksPerPixel_returnsSteppedUpValue()
    {
        // Arrange — blocksPerPixel corresponding to the full 1000-chunk preview radius at a 256px image
        final double blocksPerPixel = (2.0 * 1000 * 16) / 256;

        // Act
        final int result = ChunkGridLayout.computeGridStepChunks(blocksPerPixel);

        // Assert
        assertTrue(result > 1);
        assertTrue(result * (16.0 / blocksPerPixel) >= MIN_GRID_LINE_SPACING_PIXELS);
    }

    @Test
    void computeGridStepChunks_zeroBlocksPerPixel_returnsSaneDefaultWithoutThrowing()
    {
        // Arrange
        final double blocksPerPixel = 0.0;

        // Act
        final int result = assertDoesNotThrow(() -> ChunkGridLayout.computeGridStepChunks(blocksPerPixel));

        // Assert
        assertEquals(1, result);
    }

    @Test
    void computeGridLinePixelPositions_originNotChunkAligned_alignsToRealChunkBoundaries()
    {
        // Arrange — origin deliberately not a multiple of 16, mirroring a spawn point that isn't chunk-aligned
        final int originBlock = 5;
        final double blocksPerPixel = 1.0;
        final int panelSizePixels = 64;
        final int gridStepChunks = 1;

        // Act
        final List<Integer> positions = ChunkGridLayout.computeGridLinePixelPositions(originBlock, blocksPerPixel, panelSizePixels, gridStepChunks);

        // Assert
        assertFalse(positions.isEmpty());
        for (final int pixelPos : positions)
        {
            final long worldBlock = Math.round(originBlock + (pixelPos - panelSizePixels / 2.0) * blocksPerPixel);
            assertEquals(0L, Math.floorMod(worldBlock, 16L), "Grid line at pixel " + pixelPos + " must fall on a true chunk boundary.");
        }
    }

    @Test
    void computeGridLinePixelPositions_stepGreaterThanOne_skipsIntermediateBoundaries()
    {
        // Arrange
        final int originBlock = 0;
        final double blocksPerPixel = 1.0;
        final int panelSizePixels = 128;

        // Act
        final List<Integer> everyBoundary = ChunkGridLayout.computeGridLinePixelPositions(originBlock, blocksPerPixel, panelSizePixels, 1);
        final List<Integer> everySecondBoundary = ChunkGridLayout.computeGridLinePixelPositions(originBlock, blocksPerPixel, panelSizePixels, 2);

        // Assert
        assertTrue(everySecondBoundary.size() < everyBoundary.size());
        assertTrue(everyBoundary.containsAll(everySecondBoundary));
    }
}
