package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The declaration sweep and the registry cross-check behind it.
 * <p>
 * The fixture reproduces the shape the tool exists for: an extension point whose
 * {@code .exsd} declares an attribute java-typed, an extension naming a class
 * through it, and a class nothing in Java ever mentions.
 */
class ListDeclarationsToolTest {

	private static final String TOOL = "eclipse_list_declarations";

	private static final String PROJECT = "mcp-declarations-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aClassNamedThroughAJavaTypedSchemaAttributeIsLive() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Matcher");

		assertEquals("live-via-registry", entry.get("registryStatus"));
		Map<String, Object> evidence = firstEvidence(entry);
		assertEquals("plugin.xml", evidence.get("kind"));
		assertEquals("class", evidence.get("schemaAttribute"));
		// reported exactly as the schema writes it, class:interface form included
		assertEquals("registry.AbstractMatcher:", evidence.get("basedOn"));
		assertEquals(Boolean.TRUE, evidence.get("basedOnSatisfied"));
		assertTrue(String.valueOf(evidence.get("xpathOrHeader")).endsWith("/matcher@class"),
				"the position should locate the attribute, got " + evidence.get("xpathOrHeader"));
	}

	@Test
	void anAttributeTheSchemaDoesNotCallJavaTypedKeepsNothingAlive() throws Exception {
		fixtureProject();
		// named in the same plugin.xml, as a label rather than as a class
		assertEquals("dead", declaration("registry.Mentioned").get("registryStatus"));
	}

	@Test
	void aNameInProseKeepsNothingAlive() throws Exception {
		fixtureProject();
		assertEquals("dead", declaration("registry.Unused").get("registryStatus"),
				"a class named only in a text file and a comment is not kept alive by it");
	}

	@Test
	void aRegistryEntryThatDoesNotMatchItsBasedOnIsStale() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.WrongSupertype");

		assertEquals("dead", entry.get("registryStatus"),
				"an entry naming a class that is not what the schema requires keeps nothing alive");
		assertEquals(Boolean.FALSE, firstEvidence(entry).get("basedOnSatisfied"),
				"the stale entry should still be reported, with the reason it does not count");
	}

	@Test
	void theBundleActivatorIsLive() throws Exception {
		fixtureProject();
		Map<String, Object> entry = declaration("registry.Activator");
		assertEquals("live-via-registry", entry.get("registryStatus"));
		assertEquals("Bundle-Activator", firstEvidence(entry).get("xpathOrHeader"));
		// BundleActivator is not on this fixture's build path, so the constraint cannot
		// be checked. Unverifiable must not read as refuted, or every activator in a
		// project that does not compile against OSGi would be reported dead
		assertNull(firstEvidence(entry).get("basedOnSatisfied"));
	}

	@Test
	void aClassLoadedByALiteralNameIsLiveAndOneBuiltAtRuntimeIsACaveat() throws Exception {
		fixtureProject();
		assertEquals("live-via-registry", declaration("registry.Reflected").get("registryStatus"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT));
		assertNotNull(result.get("dynamicReflectionSites"), "the concatenated load should be reported");
		assertTrue(String.join(" ", strings(result, "caveats")).contains("provisional"),
				"a dead verdict next to unresolvable reflection has to be reported as provisional");
	}

	@Test
	void membersOfALiveTypeAreUndecidableRatherThanDead() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "kinds", List.of("methods")));
		Map<String, Object> matches = find(result, "registry.Matcher#matches");
		assertEquals("undecidable", matches.get("registryStatus"),
				"the framework holds the instance and calls it, which no declaration list can see");
	}

	@Test
	void binaryTypesAreNeverListed() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 5000));
		for (Object entry : declarations(result)) {
			String name = String.valueOf(((Map<?, ?>) entry).get("name"));
			assertFalse(name.startsWith("java."),
					"the JRE is on the build path, and none of it may reach the declaration list");
		}
	}

	@Test
	void visibilityFilters() throws Exception {
		fixtureProject();
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "visibility", List.of("private")));
		assertEquals(0, declarations(result).size(), "every fixture type is public");
	}

	// --- fixture ----------------------------------------------------------

	private void fixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IProject project = javaProject.getProject();

		TestFixture.addType(javaProject, "registry", "AbstractMatcher",
				"package registry;\npublic abstract class AbstractMatcher {\n  public abstract boolean matches();\n}\n");
		TestFixture.addType(javaProject, "registry", "Matcher",
				"package registry;\npublic class Matcher extends AbstractMatcher {\n  @Override public boolean matches() { return true; }\n}\n");
		TestFixture.addType(javaProject, "registry", "WrongSupertype",
				"package registry;\npublic class WrongSupertype {\n}\n");
		TestFixture.addType(javaProject, "registry", "Mentioned",
				"package registry;\npublic class Mentioned {\n}\n");
		TestFixture.addType(javaProject, "registry", "Unused",
				"package registry;\n// registry.Unused is named here in a comment, which counts for nothing\npublic class Unused {\n}\n");
		TestFixture.addType(javaProject, "registry", "Activator",
				"package registry;\npublic class Activator {\n}\n");
		TestFixture.addType(javaProject, "registry", "Reflected",
				"package registry;\npublic class Reflected {\n}\n");
		TestFixture.addType(javaProject, "registry", "Loader",
				"""
						package registry;
						public class Loader {
						  public Class<?> literal() throws Exception { return Class.forName("registry.Reflected"); }
						  public Class<?> built(String suffix) throws Exception { return Class.forName("registry." + suffix); }
						}
						""");

		write(project, "notes.txt", "registry.Unused is mentioned in this file, which is not a registry position.");

		IFolder metaInf = project.getFolder("META-INF");
		metaInf.create(false, true, new NullProgressMonitor());
		write(project, "META-INF/MANIFEST.MF", """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-SymbolicName: registry.host;singleton:=true
				Bundle-Version: 1.0.0
				Bundle-Activator: registry.Activator
				""");

		IFolder schema = project.getFolder("schema");
		schema.create(false, true, new NullProgressMonitor());
		write(project, "schema/matchers.exsd", """
				<?xml version='1.0' encoding='UTF-8'?>
				<schema targetNamespace="registry.host">
				   <element name="matcher">
				      <complexType>
				         <attribute name="class" type="string" use="required">
				            <annotation>
				               <appInfo>
				                  <meta.attribute kind="java" basedOn="registry.AbstractMatcher:"/>
				               </appInfo>
				            </annotation>
				         </attribute>
				         <attribute name="label" type="string"/>
				      </complexType>
				   </element>
				</schema>
				""");

		write(project, "plugin.xml", """
				<?xml version="1.0" encoding="UTF-8"?>
				<?eclipse version="3.4"?>
				<plugin>
				   <extension-point id="matchers" name="Matchers" schema="schema/matchers.exsd"/>
				   <extension point="registry.host.matchers">
				      <matcher class="registry.Matcher" label="registry.Mentioned"/>
				      <matcher class="registry.WrongSupertype"/>
				   </extension>
				</plugin>
				""");
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
	}

	private static void write(IProject project, String path, String content) throws Exception {
		IFile file = project.getFile(path);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
	}

	// --- reading the answer -----------------------------------------------

	private static Map<String, Object> declaration(String name) throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "maxResults", 5000));
		return find(result, name);
	}

	private static Map<String, Object> find(Map<String, Object> result, String name) {
		for (Object entry : declarations(result)) {
			if (entry instanceof Map<?, ?> map && name.equals(map.get("name"))) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) map;
				return typed;
			}
		}
		throw new AssertionError("No declaration named %s in %s".formatted(name, result));
	}

	private static Map<String, Object> firstEvidence(Map<String, Object> entry) {
		Object evidence = entry.get("registryEvidence");
		assertNotNull(evidence, "expected registry evidence on " + entry);
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) ((List<?>) evidence).get(0);
		return first;
	}

	private static List<?> declarations(Map<String, Object> result) {
		return (List<?>) result.get("declarations");
	}

	private static List<String> strings(Map<String, Object> result, String key) {
		List<String> values = new java.util.ArrayList<>();
		for (Object value : (List<?>) result.get(key)) {
			values.add(String.valueOf(value));
		}
		return values;
	}
}
