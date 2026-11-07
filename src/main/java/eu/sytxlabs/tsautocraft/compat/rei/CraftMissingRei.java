package eu.sytxlabs.tsautocraft.compat.rei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.display.SimpleGridMenuDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;

import eu.sytxlabs.tsautocraft.client.CraftMissing;
import eu.sytxlabs.tsautocraft.client.CraftMissing.State;

/**
 * REI side of {@link CraftMissing}, called from {@code ReiTransferHandlerMixin} after Tom's REI transfer handler: tooltip with the missing / craftable items,
 * craftable slots blue and missing slots red, Ctrl + click requests the craftable ones (Tom's handler still fills the grid).
 */
public final class CraftMissingRei {
	private CraftMissingRei() {}

	private static final CategoryIdentifier<?> CRAFTING = CategoryIdentifier.of("minecraft", "plugins/crafting");
	private static final int CRAFTABLE_COLOR = 0x603F76E4, MISSING_COLOR = 0x40FF0000, BUTTON_CRAFTABLE_COLOR = 0x673F76E4;

	public static TransferHandler.Result handle(TransferHandler.Result tom, TransferHandler.Context ctx) {
		if (tom == null || !tom.isApplicable() || !CraftMissing.applies(ctx.getMenu())) return tom;
		Display recipe = ctx.getDisplay();
		if (!CRAFTING.equals(recipe.getCategoryIdentifier()) || ctx.getMinecraft().player == null) return tom;
		List<List<ItemStack>> inputs = new ArrayList<>();
		for (EntryIngredient ing : recipe.getInputEntries()) {
			List<ItemStack> l = new ArrayList<>();
			for (EntryStack<?> e : ing) {
				if (e.getValue() instanceof ItemStack is) l.add(is);
			}
			inputs.add(l);
		}
		CraftMissing.Result r = CraftMissing.analyze(ctx.getMenu(), ctx.getMinecraft().player, inputs);
		if (r == null) return tom;
		if (ctx.isActuallyCrafting()) {
			if (CraftMissing.wantsCraft()) CraftMissing.send(ctx.getMinecraft().player, r);
			return tom;
		}
		if (r.complete()) return tom;
		r.tooltip().forEach(tom::tooltip);
		if (r.missing().isEmpty()) tom.color(BUTTON_CRAFTABLE_COLOR);
		int width = recipe instanceof SimpleGridMenuDisplay g ? g.getWidth() : Integer.MAX_VALUE;
		List<State> states = r.slots();
		return tom.renderer((gr, mouseX, mouseY, delta, widgets, bounds, d) -> {
			int i = 0;
			for (Widget widget : widgets) {
				if (!(widget instanceof Slot slot) || slot.getNoticeMark() != Slot.INPUT) continue;
				State s = stateAt(states, i++, width);
				if (s != State.CRAFTABLE && s != State.MISSING) continue;
				Rectangle b = slot.getInnerBounds();
				gr.fill(b.x, b.y, b.getMaxX(), b.getMaxY(), s == State.CRAFTABLE ? CRAFTABLE_COLOR : MISSING_COLOR);
			}
		});
	}

	/** Grid slot widget index -> input entry index (narrow recipes have fewer entries per row, same mapping as Tom's handler). */
	private static State stateAt(List<State> states, int gridIndex, int width) {
		int entry = -1;
		if (width >= 3) entry = gridIndex;
		else if (gridIndex % 3 < width) entry = gridIndex / 3 * width + gridIndex % 3;
		return entry >= 0 && entry < states.size() ? states.get(entry) : State.EMPTY;
	}
}
