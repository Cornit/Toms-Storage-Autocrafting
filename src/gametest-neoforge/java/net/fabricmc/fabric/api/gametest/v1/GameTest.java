package net.fabricmc.fabric.api.gametest.v1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * NeoForge game test source set only: stand-in for Fabric's annotation (same name), so the shared tests in
 * src/gametest compile for both loaders. {@code TsAutocraftGameTestsNeoForge} reads it and registers the tests.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
	int maxTicks() default 20;

	int setupTicks() default 0;

	boolean required() default true;
}
