package eu.sytxlabs.tsautocraft.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import com.tom.storagemod.inventory.TerminalItemStack;
import com.tom.storagemod.menu.CraftingTerminalMenu;
import com.tom.storagemod.util.IAutoFillTerminal;

import eu.sytxlabs.tsautocraft.menu.CraftRequestProtocol;
import eu.sytxlabs.tsautocraft.menu.CraftableTerminal;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;

/**
 * "Craft missing" for the recipe viewer "+" in Tom's Crafting Terminal (JEI and REI, through mixins on Tom's transfer handlers):
 * every recipe input slot is checked against storage + player inventory (counted, one recipe set), missing ingredients that the network can craft
 * (craftables synced by {@code StorageTerminalMenuMixin}) are requested with Ctrl + click as one batch ({@code tsac_reqs}, see {@link CraftRequestProtocol}).
 */
public final class CraftMissing {
	private CraftMissing() {}

	public enum State {
		EMPTY, AVAILABLE, CRAFTABLE, MISSING
	}

	/** @param slots state per input slot (same order as the input list), craft = craftable missing items, missing = not craftable (counts in the stacks) */
	public record Result(List<State> slots, List<ItemStack> craft, List<ItemStack> missing) {
		public boolean complete() {
			return craft.isEmpty() && missing.isEmpty();
		}

		public int missingSlots() {
			return (int) slots.stream().filter(s -> s == State.CRAFTABLE || s == State.MISSING).count();
		}

		/** Tooltip lines for the "+" button: header, craftable items (blue), missing items (red), Ctrl hint. */
		public List<Component> tooltip() {
			List<Component> l = new ArrayList<>();
			if (complete()) return l;
			l.add(Component.translatable("tooltip.tsautocraft.craft_missing.header"));
			for (ItemStack s : craft) l.add(Component.translatable("tooltip.tsautocraft.craft_missing.craftable", s.getCount(), s.getHoverName()).withStyle(ChatFormatting.AQUA));
			for (ItemStack s : missing) l.add(Component.translatable("tooltip.tsautocraft.craft_missing.missing", s.getCount(), s.getHoverName()).withStyle(ChatFormatting.RED));
			if (!craft.isEmpty()) l.add(Component.translatable("tooltip.tsautocraft.craft_missing.hint").withStyle(ChatFormatting.GRAY));
			return l;
		}
	}

	/** Storage counts by item, rebuilt only when the terminal replaces its item list. */
	private static List<TerminalItemStack> cachedList;
	private static Map<TerminalItemStack, Long> cachedCounts = Map.of();

	/** Only Tom's Crafting Terminal (our Pattern Terminal encodes, it does not craft). */
	public static boolean applies(AbstractContainerMenu menu) {
		return menu instanceof CraftingTerminalMenu && menu instanceof CraftableTerminal;
	}

	/**
	 * @param inputs item options per recipe input slot (empty list = empty slot)
	 * @return null if the menu is not a Crafting Terminal
	 */
	public static Result analyze(AbstractContainerMenu menu, Player player, List<List<ItemStack>> inputs) {
		if (!applies(menu) || !(menu instanceof IAutoFillTerminal term)) return null;
		CraftableTerminal ct = (CraftableTerminal) menu;
		Map<TerminalItemStack, Long> stored = storedCounts(term.getStoredItems());
		Map<TerminalItemStack, Long> used = new HashMap<>();
		Map<TerminalItemStack, Integer> craft = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
		List<State> states = new ArrayList<>();
		for (List<ItemStack> options : inputs) {
			List<ItemStack> opts = options.stream().filter(s -> s != null && !s.isEmpty()).toList();
			if (opts.isEmpty()) {
				states.add(State.EMPTY);
				continue;
			}
			State state = null;
			for (ItemStack o : opts) {
				TerminalItemStack k = key(o);
				long have = stored.getOrDefault(k, 0L) + inventoryCount(player, o) - used.getOrDefault(k, 0L);
				if (have > 0) {
					used.merge(k, 1L, Long::sum);
					state = State.AVAILABLE;
					break;
				}
			}
			if (state == null) {
				for (ItemStack o : opts) {
					TerminalItemStack k = key(o);
					if (ct.tsac$isCraftable(k)) {
						craft.merge(k, 1, Integer::sum);
						state = State.CRAFTABLE;
						break;
					}
				}
			}
			if (state == null) {
				missing.merge(key(opts.getFirst()), 1, Integer::sum);
				state = State.MISSING;
			}
			states.add(state);
		}
		return new Result(states, stacks(craft), stacks(missing));
	}

	/** Ctrl + click on "+": request the craftable missing items (one message, one rate limit token on the server). */
	public static boolean wantsCraft() {
		return Minecraft.getInstance().hasControlDown();
	}

	public static void send(Player player, Result r) {
		if (r == null || r.craft().isEmpty()) return;
		MenuUtil.sendToServer(player, o -> {
			var l = o.list(CraftRequestProtocol.BATCH, ItemStack.CODEC);
			r.craft().stream().limit(CraftRequestProtocol.MAX_BATCH).forEach(l::add);
		});
	}

	private static TerminalItemStack key(ItemStack s) {
		return new TerminalItemStack(s.copyWithCount(1));
	}

	private static Map<TerminalItemStack, Long> storedCounts(List<TerminalItemStack> list) {
		if (list != cachedList) {
			Map<TerminalItemStack, Long> m = new HashMap<>();
			for (TerminalItemStack s : list) {
				if (s != null && s.getQuantity() > 0) m.merge(key(s.getStack()), s.getQuantity(), Long::sum);
			}
			cachedList = list;
			cachedCounts = m;
		}
		return cachedCounts;
	}

	private static long inventoryCount(Player player, ItemStack item) {
		long n = 0;
		for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
			if (ItemStack.isSameItemSameComponents(s, item)) n += s.getCount();
		}
		return n;
	}

	private static List<ItemStack> stacks(Map<TerminalItemStack, Integer> counts) {
		List<ItemStack> l = new ArrayList<>();
		counts.forEach((k, n) -> l.add(k.getStack().copyWithCount(n)));
		return l;
	}
}
