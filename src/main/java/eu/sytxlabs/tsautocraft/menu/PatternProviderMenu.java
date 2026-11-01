package eu.sytxlabs.tsautocraft.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.pattern.PatternEncoder;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;

/** 9 pattern slots in dispenser layout + toggle for blocking mode (button id 0). */
public class PatternProviderMenu extends AbstractContainerMenu implements IDataReceiver {
	private final PatternProviderBlockEntity be;
	private final Inventory inv;
	private final Container patterns;
	public boolean blocking = true;
	public boolean substitutes;

	public PatternProviderMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public PatternProviderMenu(int id, Inventory inv, PatternProviderBlockEntity be) {
		super(ModContent.PATTERN_PROVIDER_MENU, id);
		this.be = be;
		this.inv = inv;
		this.patterns = be != null ? be.getPatternInventory() : new SimpleContainer(PatternProviderBlockEntity.SLOTS);

		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 3; ++col) {
				addSlot(new Slot(patterns, col + row * 3, 62 + col * 18, 17 + row * 18) {
					@Override
					public boolean mayPlace(ItemStack stack) {
						return stack.getItem() instanceof PatternItem;
					}

					@Override
					public int getMaxStackSize() {
						return 1;
					}
				});
			}
		}
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, 84);

		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null && be.isBlocking() ? 1 : 0;
			}

			@Override
			public void set(int value) {
				blocking = value != 0;
			}
		});
		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null && be.isSubstitutes() ? 1 : 0;
			}

			@Override
			public void set(int value) {
				substitutes = value != 0;
			}
		});
	}

	/** 0 = blocking mode, 1 = substitutes for JEI-encoded patterns. */
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (be == null) return false;
		if (id == 0) be.setBlocking(!be.isBlocking());
		else if (id == 1) be.setSubstitutes(!be.isSubstitutes());
		else return false;
		return true;
	}

	/** Server: JEI "+" ({@code jei}, same data as the Pattern Terminal transfer) encodes the recipe straight into a free slot. */
	@Override
	public void receive(ValueInput in) {
		if (be == null || !in.getBooleanOr("jei", false) || inv.player.isSpectator()) return;
		Player player = inv.player;
		String error = encodeFromJei(player, in);
		player.sendOverlayMessage(Component.translatable(error != null ? error : "msg.tsautocraft.encode.ok"));
	}

	private String encodeFromJei(Player player, ValueInput in) {
		int slot = freePatternSlot();
		if (slot < 0) return "msg.tsautocraft.jei.provider_full";
		int blank = player.hasInfiniteMaterials() ? -2 : blankPatternSlot(player.getInventory());
		if (blank == -1) return "msg.tsautocraft.encode.no_blank";
		var r = PatternEncoder.encode(player.level(), in.getBooleanOr("jei_craft", true),
				in.listOrEmpty("jei_in", ItemStack.OPTIONAL_CODEC).stream().toList(),
				in.listOrEmpty("jei_out", ItemStack.OPTIONAL_CODEC).stream().toList(),
				be.isSubstitutes());
		if (r.error() != null) return r.error();
		if (blank >= 0) player.getInventory().removeItem(blank, 1);
		patterns.setItem(slot, r.toItem());
		broadcastChanges();
		return null;
	}

	/** First empty pattern slot, -1 if all are used. */
	public int freePatternSlot() {
		for (int i = 0; i < PatternProviderBlockEntity.SLOTS; i++) if (patterns.getItem(i).isEmpty()) return i;
		return -1;
	}

	/** Inventory slot holding a Blank Pattern, -1 if there is none. */
	public static int blankPatternSlot(Inventory inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(ModContent.BLANK_PATTERN)) return i;
		return -1;
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		int n = PatternProviderBlockEntity.SLOTS;
		if (index < n) {
			if (!moveItemStackTo(stack, n, slots.size(), true)) return ItemStack.EMPTY;
		} else if (stack.getItem() instanceof PatternItem) {
			if (!moveItemStackTo(stack, 0, n, false)) return ItemStack.EMPTY;
		} else {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}
}
