package eu.sytxlabs.tsautocraft.compat.jei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;

import eu.sytxlabs.tsautocraft.client.CraftMissing;
import eu.sytxlabs.tsautocraft.client.CraftMissing.State;

/**
 * JEI side of {@link CraftMissing}, called from {@code CraftingTerminalTransferHandlerMixin} after Tom's own transfer handler (which fills the grid):
 * replaces Tom's "items missing" warning with one that counts amounts and knows craftables, and requests the craftable ones on Ctrl + click.
 */
public final class CraftMissingTransfer {
	private CraftMissingTransfer() {}

	private static final int CRAFTABLE_COLOR = 0x663F76E4, MISSING_COLOR = 0x66FF0000, BUTTON_CRAFTABLE_COLOR = 0x803F76E4;

	/** @return the error to show instead of Tom's result */
	public static IRecipeTransferError transfer(IRecipeTransferError tom, AbstractContainerMenu menu, IRecipeSlotsView slots, Player player, boolean doTransfer) {
		if (tom != null && tom.getType() == IRecipeTransferError.Type.INTERNAL) return tom;
		List<IRecipeSlotView> views = slots.getSlotViews(RecipeIngredientRole.INPUT);
		List<List<ItemStack>> inputs = new ArrayList<>();
		for (IRecipeSlotView v : views) inputs.add(v.getItemStacks().toList());
		CraftMissing.Result r = CraftMissing.analyze(menu, player, inputs);
		if (r == null) return tom;
		if (doTransfer && CraftMissing.wantsCraft()) CraftMissing.send(player, r);
		return r.complete() ? null : new Warning(r, views);
	}

	/** Cosmetic (the "+" still fills the grid): craftable slots blue, missing slots red, item list in the tooltip. */
	private record Warning(CraftMissing.Result result, List<IRecipeSlotView> views) implements IRecipeTransferError {

		@Override
		public Type getType() {
			return Type.COSMETIC;
		}

		@Override
		public int getButtonHighlightColor() {
			return result.missing().isEmpty() ? BUTTON_CRAFTABLE_COLOR : DEFAULT_BUTTON_HIGHLIGHT_COLOR;
		}

		@Override
		public void showError(GuiGraphicsExtractor g, int mouseX, int mouseY, IRecipeSlotsView recipeSlotsView, int recipeX, int recipeY) {
			g.pose().pushMatrix();
			g.pose().translate(recipeX, recipeY);
			for (int i = 0; i < views.size() && i < result.slots().size(); i++) {
				State s = result.slots().get(i);
				if (s == State.CRAFTABLE) views.get(i).drawHighlight(g, CRAFTABLE_COLOR);
				else if (s == State.MISSING) views.get(i).drawHighlight(g, MISSING_COLOR);
			}
			g.pose().popMatrix();
		}

		@Override
		public void getTooltip(ITooltipBuilder tooltip) {
			tooltip.addAll(result.tooltip());
		}

		@Override
		public int getMissingCountHint() {
			return result.missingSlots();
		}
	}
}
