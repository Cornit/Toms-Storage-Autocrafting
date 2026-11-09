package eu.sytxlabs.tsautocraft.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

import net.minecraft.network.chat.Component;

import net.minecraft.client.gui.screens.Screen;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.TsAutocraftConfig;

/**
 * Config screen (Cloth Config ships inside Tom's Storage), opened from Mod Menu (Fabric) or the mod list (NeoForge). Built from the public fields of {@link TsAutocraftConfig}: labels {@code config.tsautocraft.<field>}, tooltips {@code config.tsautocraft.<field>.desc}.
 */
public final class ConfigScreen {
	private ConfigScreen() {}

	public static Screen build(Screen parent) {
		TsAutocraftConfig cfg = TsAutocraftConfig.get();
		TsAutocraftConfig def = TsAutocraftConfig.defaults();
		ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Component.translatable("config.tsautocraft.title")).setSavingRunnable(TsAutocraftConfig::save);
		ConfigEntryBuilder eb = builder.entryBuilder();
		for (Field f : TsAutocraftConfig.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("config.tsautocraft.category." + category(f.getName())));
			try {
				cat.addEntry(entry(eb, f, cfg, def));
			} catch (ReflectiveOperationException e) {
				TsAutocraft.LOGGER.error("Config field {}", f.getName(), e);
			}
		}
		return builder.build();
	}

	private static String category(String field) {
		if (field.startsWith("crafter") || field.startsWith("provider") || field.equals("enableDisabledCrafterSlots")) return "crafter";
		if (field.startsWith("requester")) return "requester";
		if (field.startsWith("maintainer")) return "maintainer";
		if (field.startsWith("io") || field.startsWith("craftables")) return "io";
		return "jobs";
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> entry(ConfigEntryBuilder eb, Field f, TsAutocraftConfig cfg, TsAutocraftConfig def) throws ReflectiveOperationException {
		String key = "config.tsautocraft." + f.getName();
		Component label = Component.translatable(key);
		Component tip = Component.translatable(key + ".desc");
		Class<?> t = f.getType();
		if (t == int.class) {
			return eb.startIntField(label, f.getInt(cfg)).setDefaultValue(f.getInt(def)).setMin(0).setTooltip(tip).setSaveConsumer(v -> set(f, cfg, v)).build();
		}
		if (t == boolean.class) {
			return eb.startBooleanToggle(label, f.getBoolean(cfg)).setDefaultValue(f.getBoolean(def)).setTooltip(tip).setSaveConsumer(v -> set(f, cfg, v)).build();
		}
		if (t.isEnum()) {
			Class<Enum> e = (Class<Enum>) t;
			return eb.startEnumSelector(label, e, (Enum) f.get(cfg)).setDefaultValue((Enum) f.get(def)).setTooltip(tip).setEnumNameProvider(v -> Component.translatable(key + "." + v.name().toLowerCase(Locale.ROOT))).setSaveConsumer(v -> set(f, cfg, v)).build();
		}
		throw new IllegalArgumentException("Unsupported config type " + t);
	}

	private static void set(Field f, Object cfg, Object v) {
		try {
			f.set(cfg, v);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}
}
