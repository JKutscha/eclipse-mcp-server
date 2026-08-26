package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools;

/**
 * The geometry of composing a shell capture from its children.
 * <p>
 * This run is headless, so nothing here can produce a real capture; what is
 * testable is which children qualify for the composition and where they land.
 * That the painting itself works needs a real IDE and stays unproven here.
 */
class ScreenshotCompositionTest {

	@Test
	void childrenAtTheirOffsetsKeepTheirPlace() {
		ScreenshotTools.Capture.Placement toolbar = ScreenshotTools.Capture.placementOf(0, 0, 1332, 37, true);
		ScreenshotTools.Capture.Placement content = ScreenshotTools.Capture.placementOf(0, 37, 1332, 663, true);
		assertPlacement(toolbar, 0, 0, 1332, 37);
		assertPlacement(content, 0, 37, 1332, 663);
	}

	@Test
	void anInvisibleChildIsLeftOut() {
		assertNull(ScreenshotTools.Capture.placementOf(0, 700, 1332, 57, false));
	}

	@Test
	void aZeroSizedChildIsLeftOut() {
		assertNull(ScreenshotTools.Capture.placementOf(0, 700, 0, 57, true));
		assertNull(ScreenshotTools.Capture.placementOf(0, 700, 1332, 0, true));
	}

	@Test
	void aSingleChildDialogIsPlacedWhole() {
		ScreenshotTools.Capture.Placement only = ScreenshotTools.Capture.placementOf(8, 8, 400, 300, true);
		assertNotNull(only);
		assertPlacement(only, 8, 8, 400, 300);
	}

	@Test
	void theMeasuredWindowComposesToItsClientArea() {
		// the shape from the defect report: a top trim bar, the content composite
		// and a bottom trim, whose edges together reach the client area's height
		List<ScreenshotTools.Capture.Placement> placed = placements(new int[][] { { 0, 0, 1332, 37 },
				{ 0, 37, 1332, 663 }, { 0, 700, 1332, 57 } });
		assertEquals(3, placed.size());
		assertEquals(37 + 663 + 57, placed.get(2).y() + placed.get(2).height());
	}

	@Test
	void theCompositionCanvasIsNeverEmpty() {
		ScreenshotTools.Capture.Size full = ScreenshotTools.Capture.compositionSize(1332, 663);
		assertEquals(1332, full.width());
		assertEquals(663, full.height());
		ScreenshotTools.Capture.Size degenerate = ScreenshotTools.Capture.compositionSize(0, 0);
		assertEquals(1, degenerate.width());
		assertEquals(1, degenerate.height());
	}

	private static List<ScreenshotTools.Capture.Placement> placements(int[][] children) {
		List<ScreenshotTools.Capture.Placement> placed = new ArrayList<>();
		for (int[] child : children) {
			ScreenshotTools.Capture.Placement at = ScreenshotTools.Capture.placementOf(child[0], child[1], child[2],
					child[3], true);
			if (at != null) {
				placed.add(at);
			}
		}
		return placed;
	}

	private static void assertPlacement(ScreenshotTools.Capture.Placement placement, int x, int y, int width,
			int height) {
		assertNotNull(placement);
		assertEquals(x, placement.x());
		assertEquals(y, placement.y());
		assertEquals(width, placement.width());
		assertEquals(height, placement.height());
	}
}
