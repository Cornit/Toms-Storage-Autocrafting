package eu.sytxlabs.tsautocraft.api;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.state.BlockState;

import eu.sytxlabs.tsautocraft.TsAutocraft;

/**
 * {@link MachineProfile}s by target block. Profiles registered in code win over datapack ones; among datapack files the first matching file (by id) wins. Datapacks are reloaded with {@code /reload}.
 */
public final class MachineProfiles {
	private record Entry(Predicate<BlockState> blocks, MachineProfile profile) {}

	/** A datapack file: block ids or {@code #tags}, plus the profile fields. */
	private record DataEntry(List<String> blocks, MachineProfile profile) {
		static final Codec<DataEntry> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.STRING.listOf().fieldOf("blocks").forGetter(DataEntry::blocks), MachineProfile.MAP_CODEC.forGetter(DataEntry::profile)).apply(i, DataEntry::new));
	}

	private static final List<Entry> CODE = new ArrayList<>();
	private static volatile List<Entry> data = List.of();
	/** Result per block state (states are canonical instances); cleared on register and reload (tags may change). */
	private static final java.util.Map<BlockState, MachineProfile> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private MachineProfiles() {}

	/** Profile for every target block {@code blocks} accepts. */
	public static synchronized void register(Predicate<BlockState> blocks, MachineProfile profile) {
		CODE.add(new Entry(blocks, profile));
		CACHE.clear();
	}

	/** Profile for the machine, {@link MachineProfile#DEFAULT} if none matches. */
	public static MachineProfile find(BlockState state) {
		return CACHE.computeIfAbsent(state, MachineProfiles::lookup);
	}

	private static MachineProfile lookup(BlockState state) {
		for (Entry e : CODE) if (e.blocks().test(state)) return e.profile();
		for (Entry e : data) if (e.blocks().test(state)) return e.profile();
		return MachineProfile.DEFAULT;
	}

	/** Server data reload listener for {@code tsautocraft/machines}. */
	public static final ResourceManagerReloadListener RELOADER = MachineProfiles::reload;

	private static void reload(ResourceManager rm) {
		List<Entry> list = new ArrayList<>();
		rm.listResources("tsautocraft/machines", id -> id.getPath().endsWith(".json")).entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> {
			try (Reader r = e.getValue().openAsReader()) {
				DataEntry d = DataEntry.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(r)).getOrThrow();
				list.add(new Entry(matcher(d.blocks()), d.profile()));
			} catch (Exception ex) {
				TsAutocraft.LOGGER.error("Invalid machine profile {}", e.getKey(), ex);
			}
		});
		data = List.copyOf(list);
		CACHE.clear();
		TsAutocraft.LOGGER.info("Loaded {} machine profiles", list.size());
	}

	private static Predicate<BlockState> matcher(List<String> blocks) {
		List<Predicate<BlockState>> parts = new ArrayList<>();
		for (String b : blocks) {
			if (b.startsWith("#")) {
				TagKey<net.minecraft.world.level.block.Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(b.substring(1)));
				parts.add(s -> s.is(tag));
			} else {
				Identifier id = Identifier.parse(b);
				parts.add(s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(id));
			}
		}
		return s -> parts.stream().anyMatch(p -> p.test(s));
	}
}
