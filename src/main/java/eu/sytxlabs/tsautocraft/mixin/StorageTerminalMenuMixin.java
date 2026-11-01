package eu.sytxlabs.tsautocraft.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.StoredItemStack;
import com.tom.storagemod.inventory.TerminalItemStack;
import com.tom.storagemod.menu.StorageTerminalMenu;
import com.tom.storagemod.util.TerminalSyncManager;

import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.menu.CraftRequestProtocol;
import eu.sytxlabs.tsautocraft.menu.CraftableTerminal;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;

/**
 * Autocrafting in Tom's terminals (Storage, Crafting and our Pattern Terminal):
 * <ul>
 *   <li>Server: sends the craftable items of the network ({@code tsac_craft}) and handles preview/request
 *       messages through {@link CraftRequestProtocol}.</li>
 *   <li>Client: craftables that are not in storage are added to the item list with amount 0.</li>
 * </ul>
 */
@Mixin(value = StorageTerminalMenu.class, remap = false)
public abstract class StorageTerminalMenuMixin implements CraftableTerminal {

	@Shadow protected StorageTerminalBlockEntity te;
	@Shadow protected Inventory pinv;
	@Shadow public List<TerminalItemStack> itemList;
	@Shadow public List<TerminalItemStack> itemListClient;
	@Shadow public boolean noSort;
	@Shadow public TerminalSyncManager sync;

	// Server
	@Unique private int tsac$timer;
	@Unique private List<ItemStack> tsac$sent;
	// Client
	/** TerminalItemStack, not StoredItemStack: StoredItemStack.equals compares getClass(). */
	@Unique private final Set<TerminalItemStack> tsac$craftables = new HashSet<>();
	@Unique private List<TerminalItemStack> tsac$listBefore;

	@Inject(method = "broadcastChanges", at = @At("HEAD"))
	private void tsac$syncCraftables(CallbackInfo ci) {
		if (te == null || te.getLevel() == null || tsac$timer-- > 0) return;
		tsac$timer = TsAutocraftConfig.get().craftablesSyncTicks;
		List<ItemStack> list = TerminalCrafting.craftables(te.getLevel(), te.getBlockPos());
		if (tsac$sent != null && tsac$sameItems(tsac$sent, list)) return;
		tsac$sent = list;
		MenuUtil.sendToClient(pinv.player, out -> {
			out.putBoolean("tsac_c", true); // an empty list may not be written at all
			var l = out.list("tsac_craft", ItemStack.OPTIONAL_CODEC);
			list.forEach(l::add);
		});
	}

	@Unique
	private static boolean tsac$sameItems(List<ItemStack> a, List<ItemStack> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(a.get(i), b.get(i))) return false;
		}
		return true;
	}

	@Inject(method = "receive", at = @At("HEAD"))
	private void tsac$receiveRequest(ValueInput in, CallbackInfo ci) {
		if (te == null) return;
		CraftRequestProtocol.receive(in, pinv.player, CraftRequestProtocol.network(te.getLevel(), te.getBlockPos()));
	}

	@Inject(method = "receiveClientNBTPacket", at = @At("HEAD"))
	private void tsac$receiveCraftablesHead(ValueInput in, CallbackInfo ci) {
		tsac$listBefore = itemList;
		if (in.getBooleanOr("tsac_c", false)) {
			tsac$craftables.clear();
			in.listOrEmpty("tsac_craft", ItemStack.OPTIONAL_CODEC).stream()
					.filter(s -> !s.isEmpty()).forEach(s -> tsac$craftables.add(new TerminalItemStack(s.copyWithCount(1), 0)));
			tsac$listBefore = null; // force a merge
		}
	}

	@Inject(method = "receiveClientNBTPacket", at = @At("TAIL"))
	private void tsac$receiveCraftablesTail(ValueInput in, CallbackInfo ci) {
		if (tsac$listBefore == itemList) return; // neither storage nor craftables changed
		tsac$listBefore = null;
		List<TerminalItemStack> merged = sync.getAsList();
		Set<TerminalItemStack> present = new HashSet<>(merged);
		for (TerminalItemStack c : tsac$craftables) {
			if (!present.contains(c)) merged.add(new TerminalItemStack(c.getStack(), 0));
		}
		itemList = merged;
		if (!noSort) itemListClient = new ArrayList<>(merged);
	}

	@Override
	public boolean tsac$isCraftable(StoredItemStack stack) {
		if (stack == null) return false;
		return tsac$craftables.contains(stack instanceof TerminalItemStack t ? t : new TerminalItemStack(stack));
	}
}
