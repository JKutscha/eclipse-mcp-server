package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * JDT's own clean-ups, driven headlessly.
 */
class CleanUpToolTest {

	private static final String TOOL = "eclipse_clean_up";

	private static final String PROJECT = "mcp-cleanup-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void convertsAnAnonymousClassToALambda() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					Runnable make() {
						return new Runnable() {
							@Override
							public void run() {
								System.out.println("x");
							}
						};
					}
				}
				""");
		TestFixture.build(project.getProject());
		String path = "/" + PROJECT + "/src/example/Holder.java";

		Map<String, Object> dry = TestFixture.callAndParse(TOOL,
				Map.of("path", path, "cleanUps", List.of("cleanup.use_lambda")));
		assertEquals(Integer.valueOf(1), dry.get("filesChanged"), "got " + dry);
		assertTrue(TestFixture.read(project.getProject().getFile("src/example/Holder.java"))
				.contains("new Runnable()"), "a dry run must not rewrite the file");

		TestFixture.callAndParse(TOOL,
				Map.of("path", path, "cleanUps", List.of("cleanup.use_lambda"), "dryRun", Boolean.FALSE));

		String source = TestFixture.read(project.getProject().getFile("src/example/Holder.java"));
		// JDT's own transformation, so the result is what Source > Clean Up produces
		assertTrue(source.contains("->"), "expected a lambda, got " + source);
	}

	@Test
	void reportsNoEditsWhereThePatternDoesNotApply() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					int value() { return 1; }
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path",
				"/" + PROJECT + "/src/example/Holder.java", "cleanUps", List.of("cleanup.use_lambda")));

		// a clean-up changes only what it can prove safe, so nothing to do is an
		// answer rather than a failure
		assertEquals(Integer.valueOf(0), result.get("filesChanged"), "got " + result);
		assertEquals(Integer.valueOf(0), result.get("edits"));
	}

	@Test
	void refusesAnUnknownCleanUpWithTheListOfKnownOnes() throws Exception {
		fixture.createJavaProject(PROJECT);

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("path", "/" + PROJECT + "/src/example/Holder.java", "cleanUps", List.of("cleanup.invented")));

		assertTrue(result.isError());
		assertTrue(result.text().contains("cleanup.use_lambda"), "the refusal should list what is offered: " + result.text());
	}
}
