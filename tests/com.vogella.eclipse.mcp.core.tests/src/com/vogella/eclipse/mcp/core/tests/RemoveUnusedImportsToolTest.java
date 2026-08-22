package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Removing exactly what the compiler calls unused.
 */
class RemoveUnusedImportsToolTest {

	private static final String TOOL = "eclipse_remove_unused_imports";

	private static final String PROJECT = "mcp-unused-imports-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void removesOnlyTheDeadImportAndLeavesTheOrderAlone() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Holder", """
				package example;

				import java.util.Map;
				import java.util.List;

				public class Holder {
					List<String> values;
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> dry = TestFixture.callAndParse(TOOL,
				Map.of("path", "/" + PROJECT + "/src/example/Holder.java"));
		assertEquals(Integer.valueOf(1), dry.get("unusedImports"), "got " + dry);
		assertEquals(Integer.valueOf(0), dry.get("removed"), "a dry run removes nothing");

		Map<String, Object> applied = TestFixture.callAndParse(TOOL,
				Map.of("path", "/" + PROJECT + "/src/example/Holder.java", "dryRun", Boolean.FALSE));
		assertEquals(Integer.valueOf(1), applied.get("removed"), "got " + applied);

		String source = TestFixture.read(project.getProject().getFile("src/example/Holder.java"));
		assertFalse(source.contains("import java.util.Map;"), source);
		// the surviving import keeps its place: not sorting is the whole difference
		// from eclipse_organize_imports, which would rewrite the block
		assertTrue(source.contains("import java.util.List;"), source);
	}

	@Test
	void findsNothingWhenEveryImportIsUsed() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Holder", """
				package example;

				import java.util.List;

				public class Holder {
					List<String> values;
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", "/" + PROJECT + "/src/example/Holder.java", "dryRun", Boolean.FALSE));

		assertEquals(Integer.valueOf(0), result.get("unusedImports"), "got " + result);
	}

	@Test
	void keepsAnImportUsedOnlyFromJavadoc() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Holder", """
				package example;

				import java.util.Map;

				/** See {@link Map} for the shape of it. */
				public class Holder {
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", "/" + PROJECT + "/src/example/Holder.java", "dryRun", Boolean.FALSE));

		// the compiler decides, so the project's javadoc settings decide. A remover
		// that reasons about code alone leaves "Javadoc: Map cannot be resolved"
		assertEquals(Integer.valueOf(0), result.get("unusedImports"), "got " + result);
		assertTrue(TestFixture.read(project.getProject().getFile("src/example/Holder.java"))
				.contains("import java.util.Map;"));
	}
}
