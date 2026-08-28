package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.internal.SubstituteBundleTool;

/**
 * Which file a bundles.info line names.
 * <p>
 * This is the question that decides whether a packed jar may be deleted, and
 * getting it wrong deletes the jar the next start would have loaded.
 */
class SubstituteBundleToolTest {

	private static final Path CONFIGURATION = Path.of("/opt/eclipse/configuration");

	@Test
	void resolvesARelativePathAgainstTheInstallation() {
		Path jar = SubstituteBundleTool.jarOf(CONFIGURATION,
				"org.eclipse.core.resources,3.24.0,plugins/org.eclipse.core.resources_3.24.0.jar,4,false");

		assertEquals(Path.of("/opt/eclipse/plugins/org.eclipse.core.resources_3.24.0.jar"), jar);
	}

	@Test
	void resolvesAFileUri() {
		Path jar = SubstituteBundleTool.jarOf(CONFIGURATION,
				"org.eclipse.core.resources,3.24.0,file:///opt/eclipse/configuration/mcp-substituted/x.jar,4,false");

		assertEquals(Path.of("/opt/eclipse/configuration/mcp-substituted/x.jar"), jar);
	}

	@Test
	void namesNoFileForALineThatIsNotOne() {
		assertNull(SubstituteBundleTool.jarOf(CONFIGURATION, "#encoding=UTF-8"));
		assertNull(SubstituteBundleTool.jarOf(CONFIGURATION, "org.eclipse.core.resources,3.24.0"));
	}
}
