package com.vogella.eclipse.mcp.core;

import java.nio.file.Path;

/**
 * Records a launched JVM with Java Flight Recorder, through the only channel
 * that reaches one.
 * <p>
 * The recording tools of this server work inside the IDE's own JVM, so they
 * cannot see a program the IDE launches: that is a separate process. Asking the
 * JVM to record itself from its command line does reach it, and costs no attach
 * mechanism and no external tool. The price is that the decision has to be made
 * before the launch and the file exists only once the program ends.
 */
public final class LaunchRecording {

	/** How a caller asks for one, and what each answer costs. */
	public static final String SCHEMA_PROPERTY = """
			{"type":"string","enum":["off","default","profile"],"default":"off","description":"Record the launched JVM with Java Flight Recorder. 'profile' includes allocation and execution samples at a few percent overhead, 'default' covers GC and threads at about one percent. The file is written when the program exits and is read with eclipse_stop_flight_recording by passing its path as 'file'. The IDE's own recording tools cannot see a launched process, which is what this is for."}"""; //$NON-NLS-1$

	private LaunchRecording() {
	}

	/** Whether this value asks for a recording at all. */
	public static boolean wanted(String settings) {
		return settings != null && !settings.isBlank() && !"off".equals(settings); //$NON-NLS-1$
	}

	/** A file to record into, named after what is being launched. */
	public static Path fileFor(String label) {
		String safe = label == null || label.isBlank() ? "launch" //$NON-NLS-1$
				: label.replaceAll("[^A-Za-z0-9._-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
		return Path.of(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
				"mcp-%s-%d.jfr".formatted(safe, Long.valueOf(System.nanoTime()))); //$NON-NLS-1$
	}

	/**
	 * The VM argument that starts the recording.
	 * <p>
	 * {@code dumponexit} is what makes this usable at all: without it a program
	 * that ends normally takes its recording with it, and there is nothing left to
	 * read.
	 */
	public static String vmArgument(String settings, Path file) {
		return "-XX:StartFlightRecording=settings=%s,dumponexit=true,filename=%s".formatted(settings, file); //$NON-NLS-1$
	}

	/** Appended rather than replaced, so a caller's own VM arguments survive. */
	public static String appendTo(String vmArguments, String argument) {
		return vmArguments == null || vmArguments.isBlank() ? argument : vmArguments + " " + argument; //$NON-NLS-1$
	}

	/** What the caller has to know to get anything out of it. */
	public static String note(Path file) {
		return "The JVM records itself into %s and writes it when it exits, so the file is not there while the program runs and a program that is killed rather than ended leaves nothing. Read it with eclipse_stop_flight_recording passing file, which aggregates it the same way it aggregates the IDE's own recordings." //$NON-NLS-1$
				.formatted(file);
	}
}
