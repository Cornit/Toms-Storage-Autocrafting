package eu.sytxlabs.tsautocraft.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.api.PatternModes;
import eu.sytxlabs.tsautocraft.client.CraftingEmitterScreen;
import eu.sytxlabs.tsautocraft.client.GhostTargets;
import eu.sytxlabs.tsautocraft.client.PatternTerminalScreen;
import eu.sytxlabs.tsautocraft.client.RequesterScreen;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;
import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu;
import eu.sytxlabs.tsautocraft.menu.RequesterMenu;

/**
 * Optional JEI integration (entrypoint {@code jei_mod_plugin}, only loaded when JEI is installed): the "+" button copies any recipe into the Pattern Terminal
 * (crafting recipes -> crafting mode with the grid positions, furnace recipes -> smelting mode, everything else -> processing mode with amounts),
 * and ingredients can be dragged onto the ghost slots of the Pattern Terminal, the Requester and the Crafting Emitter.
 */
@JeiPlugin
public class TsAutocraftJeiPlugin implements IModPlugin {

	@Override
	public Identifier getPluginUid() {
		return TsAutocraft.id("jei");
	}

	@Override
	public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
		registration.addUniversalRecipeTransferHandler(new PatternTransfer(registration.getTransferHelper()));
		registration.addUniversalRecipeTransferHandler(new RequesterTransfer(registration.getTransferHelper()));
		registration.addUniversalRecipeTransferHandler(new ProviderTransfer(registration.getTransferHelper()));
	}

	/** "+" in the Requester: the recipe's (first) output becomes a Requester entry; a new entry starts at one stack. */
	private record RequesterTransfer(IRecipeTransferHandlerHelper helper) implements IUniversalRecipeTransferHandler<RequesterMenu> {

		@Override
		public Class<? extends RequesterMenu> getContainerClass() {
			return RequesterMenu.class;
		}

		@Override
		public Optional<MenuType<RequesterMenu>> getMenuType() {
			return Optional.of(ModContent.REQUESTER_MENU);
		}

		@Override
		public IRecipeTransferError transferRecipe(RequesterMenu menu, Object recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer) {
			ItemStack out = slots.getSlotViews(RecipeIngredientRole.OUTPUT).stream().map(TsAutocraftJeiPlugin::stackOf).filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
			if (out.isEmpty()) return helper.createUserErrorWithTooltip(Component.translatable("msg.tsautocraft.jei.no_items"));
			if (menu.entryFor(out) < 0) return helper.createUserErrorWithTooltip(Component.translatable("msg.tsautocraft.jei.requester_full"));
			if (!doTransfer) return null;
			MenuUtil.sendToServer(player, o -> o.store("jei_req", ItemStack.CODEC, out.copyWithCount(1)));
			return null;
		}
	}

	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration registration) {
		registration.addGhostIngredientHandler(PatternTerminalScreen.class, new GhostHandler<>());
		registration.addGhostIngredientHandler(RequesterScreen.class, new GhostHandler<>());
		registration.addGhostIngredientHandler(CraftingEmitterScreen.class, new GhostHandler<>());
	}

	/** Ghost slots of the Pattern Terminal, Requester and Crafting Emitter (see {@link GhostTargets}). */
	private static class GhostHandler<T extends Screen> implements IGhostIngredientHandler<T> {

		@Override
		public <I> List<Target<I>> getTargetsTyped(T gui, ITypedIngredient<I> ingredient, boolean doStart) {
			if (ingredient.getItemStack().isEmpty()) return List.of();
			List<Target<I>> targets = new ArrayList<>();
			for (GhostTargets.Target t : GhostTargets.of(gui)) {
				Rect2i area = new Rect2i(t.x(), t.y(), t.w(), t.h());
				targets.add(new Target<>() {
					@Override
					public Rect2i getArea() {
						return area;
					}
					@Override
					public void accept(I value) {
						if (value instanceof ItemStack st) t.accept().accept(st);
					}
				});
			}
			return targets;
		}

		@Override
		public void onComplete() {
		}
	}

	private static ItemStack stackOf(IRecipeSlotView view) {
		return view.getDisplayedItemStack().or(() -> view.getItemStacks().findFirst()).orElse(ItemStack.EMPTY);
	}

	private record PatternTransfer(IRecipeTransferHandlerHelper helper) implements IUniversalRecipeTransferHandler<PatternTerminalMenu> {

		@Override
		public Class<? extends PatternTerminalMenu> getContainerClass() {
			return PatternTerminalMenu.class;
		}

		@Override
		public Optional<MenuType<PatternTerminalMenu>> getMenuType() {
			return Optional.of(ModContent.PATTERN_TERMINAL_MENU);
		}

		@Override
		public IRecipeTransferError transferRecipe(PatternTerminalMenu menu, Object recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer) {
			JeiRecipe r = JeiRecipe.of(recipe, slots);
			if (r.error() != null) return helper.createUserErrorWithTooltip(Component.translatable(r.error()));
			if (doTransfer) r.send(player);
			return null;
		}
	}

	/** "+" in the Pattern Provider: encodes the recipe into a free slot (uses a Blank Pattern from the inventory). */
	private record ProviderTransfer(IRecipeTransferHandlerHelper helper) implements IUniversalRecipeTransferHandler<PatternProviderMenu> {

		@Override
		public Class<? extends PatternProviderMenu> getContainerClass() {
			return PatternProviderMenu.class;
		}

		@Override
		public Optional<MenuType<PatternProviderMenu>> getMenuType() {
			return Optional.of(ModContent.PATTERN_PROVIDER_MENU);
		}

		@Override
		public IRecipeTransferError transferRecipe(PatternProviderMenu menu, Object recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer) {
			JeiRecipe r = JeiRecipe.of(recipe, slots);
			if (r.error() != null) return helper.createUserErrorWithTooltip(Component.translatable(r.error()));
			if (menu.freePatternSlot() < 0) return helper.createUserErrorWithTooltip(Component.translatable("msg.tsautocraft.jei.provider_full"));
			if (!player.hasInfiniteMaterials() && PatternProviderMenu.blankPatternSlot(player.getInventory()) < 0) {
				return helper.createUserErrorWithTooltip(Component.translatable("msg.tsautocraft.encode.no_blank"));
			}
			if (doTransfer) r.send(player);
			return null;
		}
	}

	/** Recipe as pattern data, mode from {@link PatternModes#forRecipe} (processing if no mode claims the recipe). */
	private record JeiRecipe(PatternMode mode, List<ItemStack> in, List<ItemStack> out, String error) {
		static JeiRecipe of(Object recipe, IRecipeSlotsView slots) {
			PatternMode mode = PatternModes.forRecipe(recipe);
			boolean grid = mode.keepsGridPositions();
			List<ItemStack> in = new ArrayList<>();
			for (IRecipeSlotView v : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
				ItemStack s = stackOf(v);
				if (grid || !s.isEmpty()) in.add(s); // crafting keeps the empty grid positions
			}
			List<ItemStack> out = new ArrayList<>();
			for (IRecipeSlotView v : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
				ItemStack s = stackOf(v);
				if (!s.isEmpty()) out.add(s);
			}
			String error = null;
			if (in.stream().allMatch(ItemStack::isEmpty) || (!mode.fixedInputs() && out.isEmpty())) error = "msg.tsautocraft.jei.no_items";
			else if (in.size() > 9) error = "msg.tsautocraft.jei.too_many";
			return new JeiRecipe(mode, in, out, error);
		}

		/** Same message for the Pattern Terminal and the Pattern Provider menu. */
		void send(Player player) {
			MenuUtil.sendToServer(player, o -> {
				o.putBoolean("jei", true);
				o.putString("jei_mode", mode.id().toString());
				var li = o.list("jei_in", ItemStack.OPTIONAL_CODEC);
				in.forEach(li::add);
				var lo = o.list("jei_out", ItemStack.OPTIONAL_CODEC);
				out.stream().limit(3).forEach(lo::add);
			});
		}
	}
}
