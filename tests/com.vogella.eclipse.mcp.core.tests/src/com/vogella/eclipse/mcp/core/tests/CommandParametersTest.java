package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.CommandManager;
import org.eclipse.core.commands.IParameter;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.CommandTools;

import java.util.Map;

/**
 * Reading the parameters of a command that declares none.
 * <p>
 * {@code Command.getParameters()} answers null there rather than an empty
 * array, and most workbench commands declare no parameters: every toggle, every
 * Expand All. Reading its length unguarded therefore failed for the majority of
 * commands while working for the parameterised few, which is why this is worth
 * a test of its own rather than a null check nobody guards again.
 */
class CommandParametersTest {

	@Test
	void aCommandWithoutParametersReadsAsEmptyRatherThanNull() throws Exception {
		CommandManager manager = new CommandManager();
		Command command = manager.getCommand("com.vogella.mcp.test.toggle");
		command.define("Toggle", "A command taking no parameters, as most workbench commands do", category(manager));

		IParameter[] parameters = CommandTools.parametersFor(command);

		assertNotNull(parameters, "null is what the platform returns and what the caller must never see");
		assertEquals(0, parameters.length);
	}

	@Test
	void declaredParametersStillComeThrough() throws Exception {
		CommandManager manager = new CommandManager();
		Command command = manager.getCommand("com.vogella.mcp.test.parameterised");
		command.define("Show View", "A command with one parameter", category(manager),
				new IParameter[] { new TestParameter() });

		IParameter[] parameters = CommandTools.parametersFor(command);

		assertEquals(1, parameters.length);
		assertEquals("viewId", parameters[0].getId());
	}

	/** Defining a command without one throws, so every command has to have a category. */
	private static org.eclipse.core.commands.Category category(CommandManager manager) {
		org.eclipse.core.commands.Category category = manager.getCategory("com.vogella.mcp.test.category");
		category.define("Test", null);
		return category;
	}

	/** The smallest IParameter there can be; the platform's own are all workbench bound. */
	private static final class TestParameter implements IParameter {

		@Override
		public String getId() {
			return "viewId";
		}

		@Override
		public String getName() {
			return "View";
		}

		@Override
		public org.eclipse.core.commands.IParameterValues getValues() {
			return Map::of;
		}

		@Override
		public boolean isOptional() {
			return false;
		}
	}
}
