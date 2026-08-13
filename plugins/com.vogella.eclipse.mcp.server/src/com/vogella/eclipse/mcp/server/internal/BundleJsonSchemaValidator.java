package com.vogella.eclipse.mcp.server.internal;

import java.util.Map;
import java.util.function.Supplier;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;

/**
 * The schema validator, with the meta-schema lookup made to work under OSGi.
 * <p>
 * The underlying library reads its bundled meta-schemas through the context class loader
 * and only falls back to its own class loader when there is none. Inside Equinox the
 * context class loader cannot see the library's resources, so it is cleared for the
 * duration of every call.
 */
public final class BundleJsonSchemaValidator implements JsonSchemaValidator {

	private final JsonSchemaValidator delegate;

	public BundleJsonSchemaValidator() {
		delegate = withoutContextClassLoader(() -> new JacksonJsonSchemaValidatorSupplier().get());
	}

	@Override
	public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
		return withoutContextClassLoader(() -> delegate.validate(schema, structuredContent));
	}

	@Override
	public ValidationResponse validateSchema(Map<String, Object> schema) {
		return withoutContextClassLoader(() -> delegate.validateSchema(schema));
	}

	private static <T> T withoutContextClassLoader(Supplier<T> supplier) {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(null);
		try {
			return supplier.get();
		} finally {
			thread.setContextClassLoader(previous);
		}
	}
}
