package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.LaunchRecording;

/**
 * The VM argument that records a launched program.
 * <p>
 * It is the only way this server reaches another process: everything else it
 * records runs inside the IDE's own JVM.
 */
class LaunchRecordingTest {

	@Test
	void theArgumentDumpsOnExit() {
		String argument = LaunchRecording.vmArgument("profile", Path.of("/tmp/run.jfr"));

		assertTrue(argument.startsWith("-XX:StartFlightRecording="), argument);
		assertTrue(argument.contains("settings=profile"), argument);
		assertTrue(argument.contains("filename=/tmp/run.jfr"), argument);
		// without this a program that ends normally takes the recording with it and
		// there is nothing left to read, which is the whole feature
		assertTrue(argument.contains("dumponexit=true"), argument);
	}

	@Test
	void theCallersOwnVmArgumentsSurvive() {
		assertEquals("-Xmx2g -XX:StartFlightRecording=x",
				LaunchRecording.appendTo("-Xmx2g", "-XX:StartFlightRecording=x"));
		assertEquals("-XX:StartFlightRecording=x", LaunchRecording.appendTo(null, "-XX:StartFlightRecording=x"));
		assertEquals("-XX:StartFlightRecording=x", LaunchRecording.appendTo("   ", "-XX:StartFlightRecording=x"));
	}

	@Test
	void offAndAbsentBothMeanNoRecording() {
		assertFalse(LaunchRecording.wanted("off"));
		assertFalse(LaunchRecording.wanted(null));
		assertFalse(LaunchRecording.wanted(""));
		assertTrue(LaunchRecording.wanted("profile"));
		assertTrue(LaunchRecording.wanted("default"));
	}

	@Test
	void theFileNameCarriesNothingAShellWouldReadAsSyntax() {
		String name = LaunchRecording.fileFor("com.example.Main test #1").getFileName().toString();

		assertTrue(name.endsWith(".jfr"), name);
		assertFalse(name.contains(" "), name);
		assertFalse(name.contains("#"), name);
		assertTrue(name.contains("com.example.Main"), name);
	}

	@Test
	void twoLaunchesDoNotWriteToTheSameFile() {
		assertFalse(LaunchRecording.fileFor("Main").equals(LaunchRecording.fileFor("Main")));
	}

	@Test
	void theNoteSaysWhenTheFileAppears() {
		String note = LaunchRecording.note(Path.of("/tmp/run.jfr"));

		assertTrue(note.contains("/tmp/run.jfr"), note);
		assertTrue(note.contains("exits"), "the file is absent while the program runs, which surprises everyone once");
		assertTrue(note.contains("eclipse_stop_flight_recording"), note);
	}
}
