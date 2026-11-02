package eu.sytxlabs.tsautocraft.api;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Direction;

/**
 * How a Pattern Provider feeds one kind of machine with processing patterns (mod machines, brewing stands, ...).
 * Comes from a datapack ({@code data/<ns>/tsautocraft/machines/*.json}) or code ({@link MachineProfiles#register}).
 * <pre>
 * {
 *   "blocks": ["minecraft:brewing_stand", "#c:machines"],
 *   "sides": ["up", "north"],
 *   "redstone_pulse": 2,
 *   "blocking": true
 * }
 * </pre>
 *
 * @param sides         faces of the machine to insert through, tried in this order per stack (e.g. ingredient on top, bottles from the side); empty = the face that points at the provider
 * @param redstonePulse after a delivery the provider gives a redstone pulse of this many ticks to the machine (0 = none)
 * @param blocking      forces blocking mode on or off for this machine; empty = the provider's setting
 */
public record MachineProfile(List<Direction> sides, int redstonePulse, Optional<Boolean> blocking) {
	public static final MachineProfile DEFAULT = new MachineProfile(List.of(), 0, Optional.empty());

	public static final MapCodec<MachineProfile> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Direction.CODEC.listOf().optionalFieldOf("sides", List.of()).forGetter(MachineProfile::sides),
			Codec.intRange(0, 100).optionalFieldOf("redstone_pulse", 0).forGetter(MachineProfile::redstonePulse),
			Codec.BOOL.optionalFieldOf("blocking").forGetter(MachineProfile::blocking)
			).apply(i, MachineProfile::new));
	public static final Codec<MachineProfile> CODEC = MAP_CODEC.codec();

	public MachineProfile {
		sides = List.copyOf(sides);
	}
}
