package eu.sytxlabs.tsautocraft.menu;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.CraftingJob.JobView;

/**
 * Autocrafting Terminal. No item slots besides the player inventory; the craftable list and the jobs
 * are synced through Tom's data packet (every 10 ticks).
 * <p>Client -> server: preview/request through {@link CraftRequestProtocol}, {@code cancel} (job uid).
 * <br>Server -> client: {@code craft} (list), {@code jobs} (list), optionally {@code msg} + {@code missing}.
 */
public class AutocraftingTerminalMenu extends AbstractContainerMenu implements IDataReceiver {
	public static final int PLAYER_Y = 174;

	public record Craftable(ItemStack item, long stock) {
		public static final Codec<Craftable> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(Craftable::item),
				Codec.LONG.fieldOf("s").forGetter(Craftable::stock)
				).apply(i, Craftable::new));
	}

	public record Missing(ItemStack item, long count) {
		public static final Codec<Missing> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(Missing::item),
				Codec.LONG.fieldOf("c").forGetter(Missing::count)
				).apply(i, Missing::new));
	}

	private final AutocraftingTerminalBlockEntity be;
	private final Inventory inv;
	private int syncTimer;

	// Client state
	public final List<Craftable> craftables = new ArrayList<>();
	public final List<JobView> jobs = new ArrayList<>();
	public final List<Missing> missing = new ArrayList<>();
	public String message = "";
	public Runnable onUpdate;

	public AutocraftingTerminalMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public AutocraftingTerminalMenu(int id, Inventory inv, AutocraftingTerminalBlockEntity be) {
		super(ModContent.AUTOCRAFTING_TERMINAL_MENU, id);
		this.be = be;
		this.inv = inv;
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, PLAYER_Y);
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (be == null) return;
		if (syncTimer-- > 0) return;
		syncTimer = 10;
		sync(null, List.of());
	}

	private void sync(String msg, List<Missing> miss) {
		List<Craftable> list = new ArrayList<>();
		for (ItemStack st : be.craftables()) list.add(new Craftable(st, be.stockOf(st)));
		List<JobView> views = be.jobViews();
		MenuUtil.sendToClient(inv.player, out -> {
			var c = out.list("craft", Craftable.CODEC);
			list.forEach(c::add);
			var j = out.list("jobs", JobView.CODEC);
			views.forEach(j::add);
			if (msg != null) {
				out.putString("msg", msg);
				var m = out.list("missing", Missing.CODEC);
				miss.forEach(m::add);
			}
		});
	}

	@Override
	public void receive(ValueInput in) {
		if (be == null) {
			// Client: data from the server
			craftables.clear();
			in.listOrEmpty("craft", Craftable.CODEC).stream().forEach(craftables::add);
			jobs.clear();
			in.listOrEmpty("jobs", JobView.CODEC).stream().forEach(jobs::add);
			in.getString("msg").ifPresent(m -> {
				message = m;
				missing.clear();
				in.listOrEmpty("missing", Missing.CODEC).stream().forEach(missing::add);
			});
			if (onUpdate != null) onUpdate.run();
			return;
		}
		// Server: request from the client
		if (inv.player.isSpectator()) return;
		if (CraftRequestProtocol.receive(in, inv.player, new CraftRequestProtocol.Target() {
			@Override
			public CraftingJob.PlanOutcome preview(ItemStack item, long amount) {
				return be.preview(item, amount);
			}

			@Override
			public AutocraftingTerminalBlockEntity.RequestResult request(ItemStack item, long amount) {
				return be.request(item, amount);
			}
		})) {
			syncTimer = 0;
		}
		in.getInt("cancel").ifPresent(id -> {
			be.cancel(id);
			syncTimer = 0;
		});
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		// Player inventory only: move between main inventory and hotbar
		Slot slot = slots.get(index);
		if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < 27) {
			if (!moveItemStackTo(stack, 27, 36, false)) return ItemStack.EMPTY;
		} else if (!moveItemStackTo(stack, 0, 27, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}
}
