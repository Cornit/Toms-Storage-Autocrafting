package eu.sytxlabs.tsautocraft.compat.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;

import com.tom.storagemod.screen.IScreen;

import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.api.PatternModes;
import eu.sytxlabs.tsautocraft.client.AutocraftingTerminalScreen;
import eu.sytxlabs.tsautocraft.client.GhostTargets;
import eu.sytxlabs.tsautocraft.client.IOScreen;
import eu.sytxlabs.tsautocraft.client.LevelMaintainerScreen;
import eu.sytxlabs.tsautocraft.client.PatternProviderScreen;
import eu.sytxlabs.tsautocraft.menu.LevelMaintainerMenu;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;
import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu;
import eu.sytxlabs.tsautocraft.menu.RequesterMenu;

/**
 * Optional REI integration (entrypoint {@code rei_client}, only loaded when REI is installed), same features as JEI: "+" into the Pattern Terminal (fills the grid),
 * Pattern Provider (encodes into a free slot), Requester and Level Maintainer (output becomes an entry), ghost drag onto the Pattern Terminal, Requester,
 * Level Maintainer and Crafting Emitter, upgrade panels excluded.
 */
public class TsAutocraftReiPlugin implements REIClientPlugin {

	@Override
	public void registerTransferHandlers(TransferHandlerRegistry registry) {
		registry.register(new Transfer());
	}

	@Override
	public void registerScreens(ScreenRegistry registry) {
		registry.registerDraggableStackVisitor(new GhostVisitor());
	}

	@Override
	public void registerExclusionZones(ExclusionZones zones) {
		for (Class<? extends IScreen> c : List.of(PatternProviderScreen.class, IOScreen.class, AutocraftingTerminalScreen.class, LevelMaintainerScreen.class)) {
			zones.register(c, screen -> {
				List<Rectangle> l = new ArrayList<>();
				screen.getExclusionAreas(b -> l.add(new Rectangle(b.x(), b.y(), b.width(), b.height())));
				return l;
			});
		}
	}

	/** First item of every input/output slot of the display. */
	private static List<ItemStack> stacks(List<EntryIngredient> ingredients, boolean keepEmpty) {
		List<ItemStack> l = new ArrayList<>();
		for (EntryIngredient ing : ingredients) {
			ItemStack st = ItemStack.EMPTY;
			for (EntryStack<?> e : ing) {
				if (e.getValue() instanceof ItemStack is && !is.isEmpty()) {
					st = is.copy();
					break;
				}
			}
			if (keepEmpty || !st.isEmpty()) l.add(st);
		}
		return l;
	}

	private static class Transfer implements TransferHandler {

		@Override
		public Result handle(Context context) {
			var menu = context.getMenu();
			boolean terminal = menu instanceof PatternTerminalMenu, provider = menu instanceof PatternProviderMenu;
			boolean requester = menu instanceof RequesterMenu, maintainer = menu instanceof LevelMaintainerMenu;
			if (!terminal && !provider && !requester && !maintainer) return Result.createNotApplicable();
			Display display = context.getDisplay();
			Player player = context.getMinecraft().player;
			if (player == null) return Result.createNotApplicable();
			List<ItemStack> out = stacks(display.getOutputEntries(), false);

			if (requester) {
				if (out.isEmpty()) return Result.createFailed(Component.translatable("msg.tsautocraft.jei.no_items"));
				ItemStack target = out.getFirst().copyWithCount(1);
				if (((RequesterMenu) menu).entryFor(target) < 0) return Result.createFailed(Component.translatable("msg.tsautocraft.jei.requester_full"));
				if (context.isActuallyCrafting()) MenuUtil.sendToServer(player, o -> o.store("jei_req", ItemStack.CODEC, target));
				return Result.createSuccessful().blocksFurtherHandling();
			}
			if (maintainer) {
				if (out.isEmpty()) return Result.createFailed(Component.translatable("msg.tsautocraft.jei.no_items"));
				ItemStack target = out.getFirst().copyWithCount(1);
				if (((LevelMaintainerMenu) menu).entryFor(target) < 0) return Result.createFailed(Component.translatable("msg.tsautocraft.jei.maintainer_full"));
				if (context.isActuallyCrafting()) MenuUtil.sendToServer(player, o -> o.store("jei_req", ItemStack.CODEC, target));
				return Result.createSuccessful().blocksFurtherHandling();
			}

			PatternMode mode = PatternModes.forReiCategory(display.getCategoryIdentifier().getIdentifier());
			List<ItemStack> in = stacks(display.getInputEntries(), mode.keepsGridPositions());
			if (in.stream().allMatch(ItemStack::isEmpty) || (!mode.fixedInputs() && out.isEmpty())) {
				return Result.createFailed(Component.translatable("msg.tsautocraft.jei.no_items"));
			}
			if (in.size() > 9) return Result.createFailed(Component.translatable("msg.tsautocraft.jei.too_many"));
			if (provider) {
				PatternProviderMenu pm = (PatternProviderMenu) menu;
				if (pm.freePatternSlot() < 0) return Result.createFailed(Component.translatable("msg.tsautocraft.jei.provider_full"));
				if (!player.hasInfiniteMaterials() && PatternProviderMenu.blankPatternSlot(player.getInventory()) < 0) {
					return Result.createFailed(Component.translatable("msg.tsautocraft.encode.no_blank"));
				}
			}
			if (context.isActuallyCrafting()) {
				MenuUtil.sendToServer(player, o -> {
					o.putBoolean("jei", true);
					o.putString("jei_mode", mode.id().toString());
					var li = o.list("jei_in", ItemStack.OPTIONAL_CODEC);
					in.forEach(li::add);
					var lo = o.list("jei_out", ItemStack.OPTIONAL_CODEC);
					out.stream().limit(3).forEach(lo::add);
				});
			}
			return Result.createSuccessful().blocksFurtherHandling();
		}
	}

	/** Dragging an item from REI onto a ghost slot (see {@link GhostTargets}). */
	private static class GhostVisitor implements DraggableStackVisitor<Screen> {

		@Override
		public <R extends Screen> boolean isHandingScreen(R screen) {
			return GhostTargets.handles(screen);
		}

		@Override
		public Stream<BoundsProvider> getDraggableAcceptingBounds(DraggingContext<Screen> context, DraggableStack stack) {
			if (!(stack.getStack().getValue() instanceof ItemStack)) return Stream.empty();
			return GhostTargets.of(context.getScreen()).stream().map(t -> BoundsProvider.ofRectangle(new Rectangle(t.x(), t.y(), t.w(), t.h())));
		}

		@Override
		public DraggedAcceptorResult acceptDraggedStack(DraggingContext<Screen> context, DraggableStack stack) {
			if (!(stack.getStack().getValue() instanceof ItemStack item) || item.isEmpty()) return DraggedAcceptorResult.PASS;
			var pos = context.getCurrentPosition();
			if (pos == null) return DraggedAcceptorResult.PASS;
			for (GhostTargets.Target t : GhostTargets.of(context.getScreen())) {
				if (t.contains(pos.x, pos.y)) {
					t.accept().accept(item.copyWithCount(1));
					return DraggedAcceptorResult.CONSUMED;
				}
			}
			return DraggedAcceptorResult.PASS;
		}
	}
}
