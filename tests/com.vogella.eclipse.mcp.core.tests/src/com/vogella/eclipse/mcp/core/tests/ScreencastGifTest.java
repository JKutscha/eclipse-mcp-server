package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.vogella.eclipse.mcp.ui.internal.ScreencastGif;

/**
 * Assembling frames into an animated GIF, which needs no display at all.
 * <p>
 * The painting of a frame needs a workbench and stays unproven here; what is
 * checked is the part a caller reads: that every frame is in the file, that
 * each stays as long as it was told to, and that the fixed palette keeps a
 * colour recognisable rather than mapping it somewhere arbitrary.
 */
class ScreencastGifTest {

	private static final PaletteData RGB24 = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);

	@Test
	void everyFrameLandsInTheFileWithItsOwnDelay() throws Exception {
		Path out = Files.createTempFile("mcp-screencast", ".gif");
		try {
			List<ImageData> frames = List.of(frame(new RGB(30, 30, 30)), frame(new RGB(200, 60, 60)),
					frame(new RGB(60, 200, 60)));

			long bytes = ScreencastGif.write(frames, new int[] { 500, 1200, 250 }, true, out);

			assertTrue(bytes > 0);
			// read back through ImageIO: SWT's own loader goes through the native
			// pixbuf loader on GTK, which hands back the first frame only
			try (ImageInputStream in = ImageIO.createImageInputStream(out.toFile())) {
				ImageReader reader = ImageIO.getImageReaders(in).next();
				reader.setInput(in);
				assertEquals(3, reader.getNumImages(true), "every frame has to be in the file");
				// GIF stores hundredths of a second, so 1200 ms is 120 ticks
				assertEquals(50, delayOf(reader.getImageMetadata(0)));
				assertEquals(120, delayOf(reader.getImageMetadata(1)));
				assertEquals(25, delayOf(reader.getImageMetadata(2)));
				BufferedImage middle = reader.read(1);
				int rgb = middle.getRGB(5, 5);
				int red = (rgb >> 16) & 0xFF;
				int green = (rgb >> 8) & 0xFF;
				int blue = rgb & 0xFF;
				assertTrue(red > 150 && green < 100 && blue < 100,
						"the reddish frame has to stay reddish through the palette, got %d,%d,%d".formatted(
								Integer.valueOf(red), Integer.valueOf(green), Integer.valueOf(blue)));
			}
		} finally {
			Files.deleteIfExists(out);
		}
	}

	@Test
	void quantizingKeepsGreysGreyAndBlackBlack() {
		ImageData grey = ScreencastGif.quantize(frame(new RGB(128, 128, 128)));
		RGB mapped = grey.palette.getRGB(grey.getPixel(0, 0));
		assertEquals(mapped.red, mapped.green, "a grey must stay neutral, got " + mapped);
		assertEquals(mapped.green, mapped.blue, "a grey must stay neutral, got " + mapped);
		assertTrue(Math.abs(mapped.red - 128) <= 26, "got " + mapped);

		ImageData black = ScreencastGif.quantize(frame(new RGB(0, 0, 0)));
		assertEquals(new RGB(0, 0, 0), black.palette.getRGB(black.getPixel(0, 0)));
		assertEquals(8, black.depth, "SWT writes GIF only from indexed data");
	}

	private static int delayOf(IIOMetadata metadata) {
		Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
		NodeList children = root.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i) instanceof Element element && "GraphicControlExtension".equals(element.getNodeName())) {
				return Integer.parseInt(element.getAttribute("delayTime"));
			}
		}
		throw new AssertionError("no GraphicControlExtension in " + root);
	}

	private static ImageData frame(RGB color) {
		ImageData data = new ImageData(16, 12, 24, RGB24);
		int pixel = RGB24.getPixel(color);
		for (int y = 0; y < data.height; y++) {
			for (int x = 0; x < data.width; x++) {
				data.setPixel(x, y, pixel);
			}
		}
		return data;
	}
}
