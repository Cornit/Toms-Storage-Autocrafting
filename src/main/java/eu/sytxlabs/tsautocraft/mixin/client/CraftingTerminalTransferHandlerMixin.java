package eu.sytxlabs.tsautocraft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import com.tom.storagemod.jei.CraftingTerminalTransferHandler;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;

import eu.sytxlabs.tsautocraft.compat.jei.CraftMissingTransfer;

/**
 * JEI "+" in Tom's Crafting Terminal: craft missing ingredients ({@link CraftMissingTransfer}). A mixin instead of an own handler because JEI keeps one handler per
 * menu class and recipe type (the last registered wins, plugin order is not fixed). Only applied when JEI loads Tom's handler class.
 */
@Mixin(value = CraftingTerminalTransferHandler.class, remap = false)
public abstract class CraftingTerminalTransferHandlerMixin {

	// explicit descriptor: the generic bridge method transferRecipe(AbstractContainerMenu, Object, ...) must not get the injection too
	@Inject(method = "transferRecipe(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/world/item/crafting/RecipeHolder;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;", at = @At("RETURN"), cancellable = true)
	private void tsac$craftMissing(AbstractContainerMenu container, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer, CallbackInfoReturnable<IRecipeTransferError> cir) {
		cir.setReturnValue(CraftMissingTransfer.transfer(cir.getReturnValue(), container, slots, player, doTransfer));
	}
}
