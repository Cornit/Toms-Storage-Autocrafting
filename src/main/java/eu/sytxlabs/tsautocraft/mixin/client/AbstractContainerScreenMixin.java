package eu.sytxlabs.tsautocraft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.pattern.PatternData;

/**
 * While Ctrl is held, encoded patterns in any container slot are drawn as their primary output (with its amount), like
 * AE2's pattern preview. Display only: the slot still holds the pattern.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
	private ItemStack tsac$patternOutput(ItemStack stack) {
		if (stack.isEmpty() || !Minecraft.getInstance().hasControlDown()) return stack;
		PatternData p = stack.get(ModContent.PATTERN_COMPONENT);
		if (p == null) return stack;
		ItemStack out = p.primaryOutput();
		return out.isEmpty() ? stack : out;
	}
}
