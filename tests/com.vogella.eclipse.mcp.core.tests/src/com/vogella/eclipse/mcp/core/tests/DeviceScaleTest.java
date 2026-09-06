package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.DeviceScale;

/**
 * The arithmetic a device resolution capture is sized by.
 * <p>
 * This run is headless, so the capture itself cannot be exercised here; what
 * the canvas size has to be is testable, and it is the number that decides
 * whether a scaled display's pixels reach the image at all.
 */
class DeviceScaleTest {

	@Test
	void anUnscaledDisplayIsPointForPixel() {
		assertEquals(1332, DeviceScale.pixels(1332, 100));
		assertEquals(0, DeviceScale.pixels(0, 100));
	}

	@Test
	void aScaledDisplayNeedsTheWholeCanvas() {
		assertEquals(2664, DeviceScale.pixels(1332, 200));
		assertEquals(3200, DeviceScale.pixels(1600, 200));
	}

	@Test
	void aFractionalZoomRoundsRatherThanTruncates() {
		// truncating loses a row of pixels off the bottom edge, which reads as a
		// capture that is one pixel short of the widget it claims
		assertEquals(263, DeviceScale.pixels(175, 150));
		assertEquals(176, DeviceScale.pixels(117, 150));
	}
}
