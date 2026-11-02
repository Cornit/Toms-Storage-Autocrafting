package eu.sytxlabs.tsautocraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * Default item component {@code tsautocraft:description}: grey description {@code key} (blocks and upgrades, word wrapped) and,
 * for framed blocks, Tom's "Can be painted" line. Rendered through Fabric's {@code ItemComponentTooltipProviderRegistry}.
 */
public record ItemDescription(String key, boolean paintable) implements TooltipProvider {
	public static final Codec<ItemDescription> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.STRING.fieldOf("key").forGetter(ItemDescription::key), Codec.BOOL.optionalFieldOf("paintable", false).forGetter(ItemDescription::paintable)).apply(i, ItemDescription::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, ItemDescription> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ItemDescription::key, ByteBufCodecs.BOOL, ItemDescription::paintable, ItemDescription::new);

	/** Tooltips do not wrap by themselves: longer descriptions are broken into lines of about this many characters. */
	private static final int LINE_CHARS = 40;

	@Override
	public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltip, TooltipFlag flag, DataComponentGetter components) {
		if (paintable) tooltip.accept(Component.translatable("tooltip.toms_storage.paintable").withStyle(ChatFormatting.GRAY));
		for (String line : wrap(Component.translatable(key).getString(), LINE_CHARS)) tooltip.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
	}

	/** Word wrap at spaces; "\n" in the translation forces a break. */
	static List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\n")) {
			StringBuilder cur = new StringBuilder();
			for (String word : paragraph.split(" ")) {
				if (!cur.isEmpty() && cur.length() + 1 + word.length() > width) {
					lines.add(cur.toString());
					cur.setLength(0);
				}
				if (!cur.isEmpty()) cur.append(' ');
				cur.append(word);
			}
			lines.add(cur.toString());
		}
		return lines;
	}
}
