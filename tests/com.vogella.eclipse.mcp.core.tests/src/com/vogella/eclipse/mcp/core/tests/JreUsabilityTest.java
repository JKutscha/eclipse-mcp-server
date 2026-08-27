package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vogella.eclipse.mcp.core.JreUsability;

/**
 * The check that would have caught a JDK which is present but cannot compile.
 * <p>
 * The interesting property is what it does NOT do: an existence check passes on
 * exactly the install that broke a workspace, because the JDK was there. What
 * was missing sat inside it.
 */
class JreUsabilityTest {

	@Test
	void aJdkWithoutCtSymIsRefusedEvenThoughItExists(@TempDir Path jdk) throws Exception {
		Files.createDirectory(jdk.resolve("lib"));
		Files.createFile(jdk.resolve("lib/jrt-fs.jar"));

		String reason = JreUsability.reason(jdk.toFile());

		assertNotNull(reason, "an existence check would pass here, which is the whole point");
		assertTrue(reason.contains("ct.sym"), reason);
		assertTrue(reason.contains("mentions no target"), "the caller has to be told why the errors look unrelated: " + reason);
	}

	@Test
	void aCompleteJdkPasses(@TempDir Path jdk) throws Exception {
		Files.createDirectory(jdk.resolve("lib"));
		Files.createFile(jdk.resolve("lib/jrt-fs.jar"));
		Files.createFile(jdk.resolve("lib/ct.sym"));

		assertNull(JreUsability.reason(jdk.toFile()));
	}

	@Test
	void anInstallThatIsNotThereIsNamedAsSuch(@TempDir Path parent) {
		assertTrue(JreUsability.reason(new File(parent.toFile(), "gone")).contains("not a directory"));
		assertTrue(JreUsability.reason(null).contains("no install location"));
	}

	@Test
	void anInstallWithoutJrtFsIsLeftAlone(@TempDir Path jre) throws Exception {
		// no jrt-fs.jar means this is not a JDK 9 or later layout at all, and guessing
		// at what such an install can do would be inventing a warning
		Files.createDirectory(jre.resolve("lib"));

		assertNull(JreUsability.reason(jre.toFile()));
	}
}
