package eu.sytxlabs.tsautocraft.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * NeoForge test mod (gradlew :26.3-neoforge:runGameTestServer (Autocraft)): registers every {@link GameTest} method of the shared
 * test classes as a vanilla test function + test instance, on an empty 8x8x8 structure like Fabric's.
 */
@Mod(TsAutocraftGameTestsNeoForge.MOD_ID)
public final class TsAutocraftGameTestsNeoForge {
	public static final String MOD_ID = "tsautocraft_gametest";
	private static final Class<?>[] TEST_CLASSES = {AutocraftGameTests.class};

	public TsAutocraftGameTestsNeoForge(IEventBus modBus) {
		modBus.addListener(RegisterEvent.class, e -> e.register(Registries.TEST_FUNCTION, helper -> {
			for (Class<?> c : TEST_CLASSES) {
				Object instance = newInstance(c);
				for (Method m : tests(c)) helper.register(id(c, m), invoker(instance, m));
			}
		}));
		modBus.addListener(RegisterGameTestsEvent.class, e -> {
			Holder<TestEnvironmentDefinition<?>> env = e.registerEnvironment(Identifier.fromNamespaceAndPath(MOD_ID, "default"));
			Identifier structure = Identifier.fromNamespaceAndPath(MOD_ID, "empty");
			for (Class<?> c : TEST_CLASSES) {
				for (Method m : tests(c)) {
					GameTest a = m.getAnnotation(GameTest.class);
					ResourceKey<Consumer<GameTestHelper>> fn = ResourceKey.create(Registries.TEST_FUNCTION, id(c, m));
					e.registerTest(id(c, m), new FunctionGameTestInstance(fn, new TestData<>(env, structure, a.maxTicks(), a.setupTicks(), a.required())));
				}
			}
		});
	}

	private static java.util.List<Method> tests(Class<?> c) {
		return java.util.Arrays.stream(c.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(GameTest.class)).toList();
	}

	private static Identifier id(Class<?> c, Method m) {
		return Identifier.fromNamespaceAndPath(MOD_ID, (c.getSimpleName() + "/" + m.getName()).toLowerCase(Locale.ROOT));
	}

	private static Object newInstance(Class<?> c) {
		try {
			return c.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("test class " + c.getName(), ex);
		}
	}

	private static Consumer<GameTestHelper> invoker(Object instance, Method m) {
		return h -> {
			try {
				m.invoke(instance, h);
			} catch (InvocationTargetException ex) {
				if (ex.getCause() instanceof RuntimeException r) throw r;
				if (ex.getCause() instanceof Error err) throw err;
				throw new IllegalStateException(ex.getCause());
			} catch (IllegalAccessException ex) {
				throw new IllegalStateException(ex);
			}
		};
	}
}
