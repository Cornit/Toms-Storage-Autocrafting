package eu.sytxlabs.tsautocraft;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * Component {@code tsautocraft:memory_card}: settings copied from a block. {@code type} = block entity type id (settings
 * only fit the same type), {@code settings} = what {@code MemoryCardHost.saveCard} wrote, {@code filters} = filter slots
 * (ghosts and real filter items as templates), {@code upgrades} = installed upgrades (item + count).
 */
public record MemoryCardData(Identifier type, CompoundTag settings, List<ItemStack> filters, List<ItemStack> upgrades) implements TooltipProvider {
	public static final Codec<MemoryCardData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("type").forGetter(MemoryCardData::type),
			CompoundTag.CODEC.optionalFieldOf("settings", new CompoundTag()).forGetter(MemoryCardData::settings),
			ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("filters", List.of()).forGetter(MemoryCardData::filters),
			ItemStack.CODEC.listOf().optionalFieldOf("upgrades", List.of()).forGetter(MemoryCardData::upgrades)
	).apply(i, MemoryCardData::new));

	/** Name of the block type the settings belong to (block entity ids match our block ids). */
	public Component typeName() {
		return Component.translatable("block." + type.getNamespace() + "." + type.getPath());
	}

	@Override
	public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltip, TooltipFlag flag, DataComponentGetter components) {
		tooltip.accept(Component.translatable("tooltip.tsautocraft.memory_card.stored", typeName()).withStyle(ChatFormatting.AQUA));
		int up = 0;
		for (ItemStack u : upgrades) up += u.getCount();
		if (up > 0) tooltip.accept(Component.translatable("tooltip.tsautocraft.memory_card.upgrades", up).withStyle(ChatFormatting.GRAY));
	}
}
