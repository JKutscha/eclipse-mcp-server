package com.vogella.eclipse.mcp.ui.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools.Capture;
import com.vogella.eclipse.mcp.ui.internal.ScreenshotTools.Capture.Paintable;

/**
 * Records a shell or a part as a sequence of PNG frames.
 * <p>
 * Each frame is painted on the UI thread through {@code Control.print}, the
 * path {@code eclipse_screenshot} falls back to, since reading the root
 * drawable is blank under a compositing window manager. The paint is the only
 * part that has to happen there: scaling and encoding run on a thread of the
 * session's own, so a frame costs the UI thread one print and nothing else.
 */
public final class Screencast {

	private static final Screencast INSTANCE = new Screencast();

	private static final int KEEP = 20;

	private final Map<String, Session> sessions = new LinkedHashMap<>();

	private int counter;

	private Screencast() {
	}

	public static Screencast getInstance() {
		return INSTANCE;
	}

	/** One recording, from its first frame until stop. */
	public static final class Session {

		private final String id;
		private final Control control;
		private final boolean composed;
		private final String target;
		private final int intervalMillis;
		private final int maxFrames;
		private final int maxWidth;
		private final Path directory;
		private final long startedAt = System.currentTimeMillis();
		private final ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "MCP screencast encoder"); //$NON-NLS-1$
			thread.setDaemon(true);
			return thread;
		});
		private final List<Long> timestamps = new ArrayList<>();
		private final AtomicInteger written = new AtomicInteger();
		private final AtomicInteger failed = new AtomicInteger();
		private volatile String lastFailure;
		private volatile boolean running = true;
		private volatile String stoppedBy;
		private long armedAt;
		private int lateTicks;
		private long maxLatenessMillis;
		private long paintMillis;
		private int zoom = 100;
		private String frameSize;

		Session(String id, Control control, boolean composed, String target, int intervalMillis, int maxFrames,
				int maxWidth, Path directory) {
			this.id = id;
			this.control = control;
			this.composed = composed;
			this.target = target;
			this.intervalMillis = intervalMillis;
			this.maxFrames = maxFrames;
			this.maxWidth = maxWidth;
			this.directory = directory;
		}

		public String id() {
			return id;
		}

		public String target() {
			return target;
		}

		public boolean running() {
			return running;
		}

		public String stoppedBy() {
			return stoppedBy;
		}

		public int intervalMillis() {
			return intervalMillis;
		}

		public int maxWidth() {
			return maxWidth;
		}

		public Path directory() {
			return directory;
		}

		public long startedAt() {
			return startedAt;
		}

		public int zoom() {
			return zoom;
		}

		public String frameSize() {
			return frameSize;
		}

		public synchronized int frames() {
			return timestamps.size();
		}

		public int written() {
			return written.get();
		}

		public int failed() {
			return failed.get();
		}

		public String lastFailure() {
			return lastFailure;
		}

		public synchronized int lateTicks() {
			return lateTicks;
		}

		public synchronized long maxLatenessMillis() {
			return maxLatenessMillis;
		}

		/** What one frame cost the UI thread on average. */
		public synchronized long averagePaintMillis() {
			return timestamps.isEmpty() ? 0 : paintMillis / timestamps.size();
		}

		public synchronized long elapsedMillis() {
			return (timestamps.isEmpty() ? System.currentTimeMillis() : timestamps.get(timestamps.size() - 1))
					- startedAt;
		}

		/** How long each frame stayed on screen, from the recorded timestamps. */
		public synchronized int[] delaysMillis() {
			int[] delays = new int[timestamps.size()];
			for (int i = 0; i < delays.length; i++) {
				long next = i + 1 < delays.length ? timestamps.get(i + 1).longValue() : timestamps.get(i).longValue() + intervalMillis;
				delays[i] = (int) Math.max(1, next - timestamps.get(i).longValue());
			}
			return delays;
		}

		public Path frame(int index) {
			return directory.resolve("frame-%04d.png".formatted(Integer.valueOf(index))); //$NON-NLS-1$
		}

		/** Ends the recording; the reason is what the answer reports. */
		public void stop(String reason) {
			if (running) {
				running = false;
				stoppedBy = reason;
				encoder.shutdown();
			}
		}

		/** Waits for the frames still being encoded, at most the given seconds. */
		public boolean awaitEncoded(int seconds) {
			try {
				return encoder.awaitTermination(seconds, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		/** Paints one frame. UI thread only. */
		void tick(Display display) {
			if (!running) {
				return;
			}
			if (control.isDisposed()) {
				stop("disposed"); //$NON-NLS-1$
				return;
			}
			long now = System.currentTimeMillis();
			int index;
			synchronized (this) {
				// lateness is how long the timer waited past its due time for the UI
				// thread, not the paint, which is measured separately
				if (armedAt > 0) {
					long lateness = now - armedAt - intervalMillis;
					if (lateness > intervalMillis) {
						lateTicks++;
					}
					maxLatenessMillis = Math.max(maxLatenessMillis, lateness);
				}
				index = timestamps.size();
			}
			ImageData data;
			try {
				Frame painted = paint(display, control, composed);
				data = painted.data();
				zoom = painted.zoom();
				frameSize = data.width + "x" + data.height; //$NON-NLS-1$
			} catch (RuntimeException | Error e) {
				stop("error: " + e); //$NON-NLS-1$
				return;
			}
			synchronized (this) {
				timestamps.add(Long.valueOf(now));
				paintMillis += System.currentTimeMillis() - now;
				armedAt = System.currentTimeMillis();
			}
			encoder.execute(() -> encode(data, index));
			if (frames() >= maxFrames) {
				stop("maxFrames"); //$NON-NLS-1$
				return;
			}
			display.timerExec(intervalMillis, () -> tick(display));
		}

		private void encode(ImageData data, int index) {
			try {
				ImageData scaled = data;
				int snapped = Capture.crispWidth(data.width, maxWidth);
				if (data.width > snapped) {
					scaled = data.scaledTo(snapped, Math.max(1, data.height * snapped / data.width));
				}
				ImageLoader loader = new ImageLoader();
				loader.data = new ImageData[] { scaled };
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				loader.save(bytes, SWT.IMAGE_PNG);
				Files.write(frame(index), bytes.toByteArray());
				written.incrementAndGet();
			} catch (IOException | RuntimeException e) {
				failed.incrementAndGet();
				lastFailure = String.valueOf(e);
			}
		}
	}

	record Frame(ImageData data, int zoom) {
	}

	/**
	 * Paints the control at its monitor's zoom. A shell is composed from its
	 * children the way the screenshot does, because {@code Shell.print} is blank
	 * under a compositing window manager while the children print fine.
	 */
	static Frame paint(Display display, Control printable, boolean composed) {
		int zoom = Capture.zoomOf(printable);
		Rectangle own = composed ? ((Shell) printable).getClientArea() : printable.getBounds();
		Capture.Size canvas = Capture.compositionSize(own.width, own.height);
		List<Paintable> pieces = composed ? Capture.paintablesOf((Shell) printable) : null;
		Control backgroundSource = pieces != null && !pieces.isEmpty() ? pieces.get(0).control() : printable;
		Color background = backgroundOf(backgroundSource);
		Image image = new Image(display, (drawer, width, height) -> {
			drawer.setBackground(background);
			drawer.fillRectangle(0, 0, width, height);
			if (pieces == null) {
				printable.print(drawer);
				return;
			}
			for (Paintable piece : pieces) {
				Image part = new Image(display, (gc, w, h) -> {
					gc.setBackground(backgroundOf(piece.control()));
					gc.fillRectangle(0, 0, w, h);
					piece.control().print(gc);
				}, piece.at().width, piece.at().height);
				try {
					drawer.drawImage(part, piece.at().x, piece.at().y);
				} finally {
					part.dispose();
				}
			}
		}, canvas.width(), canvas.height());
		try {
			return new Frame(image.getImageData(zoom), zoom);
		} finally {
			image.dispose();
		}
	}

	private static Color backgroundOf(Control control) {
		Color own = control.getBackground();
		return own == null ? control.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND) : own;
	}

	/** Starts recording and paints the first frame. UI thread only. */
	public synchronized Session start(Display display, Control control, boolean composed, String target,
			int intervalMillis, int maxFrames, int maxWidth, Path directory) {
		String id = "screencast-" + (++counter); //$NON-NLS-1$
		Session session = new Session(id, control, composed, target, intervalMillis, maxFrames, maxWidth,
				directory);
		sessions.put(id, session);
		while (sessions.size() > KEEP) {
			String oldest = sessions.keySet().iterator().next();
			sessions.remove(oldest).stop("evicted"); //$NON-NLS-1$
		}
		session.tick(display);
		return session;
	}

	public synchronized Session find(String id) {
		return sessions.get(id);
	}

	public synchronized List<String> ids() {
		return new ArrayList<>(sessions.keySet());
	}

	public synchronized Session findLatest() {
		Session latest = null;
		for (Session session : sessions.values()) {
			latest = session;
		}
		return latest;
	}
}
