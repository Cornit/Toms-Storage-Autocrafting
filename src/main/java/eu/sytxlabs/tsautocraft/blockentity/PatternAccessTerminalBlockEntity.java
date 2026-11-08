package eu.sytxlabs.tsautocraft.blockentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.platform.PlatformBlockEntity;
import com.tom.storagemod.util.TickerUtil.TickableServer;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.CraftingProvider;
import eu.sytxlabs.tsautocraft.menu.PatternAccessTerminalMenu;
import eu.sytxlabs.tsautocraft.network.NetworkLink;
import eu.sytxlabs.tsautocraft.util.CustomName;
import eu.sytxlabs.tsautocraft.util.TerminalAccess;

/**
 * Pattern Access Terminal: shows the pattern slots of every Pattern Provider on the cable network in one GUI
 * (see {@link PatternAccessTerminalMenu}). Holds no items itself.
 */
public class PatternAccessTerminalBlockEntity extends PlatformBlockEntity implements TickableServer, MenuProvider, StatusInfo {
	private final CustomName customName = new CustomName();
	private final NetworkLink network = new NetworkLink();

	public PatternAccessTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.PATTERN_ACCESS_TERMINAL_BE, pos, state);
	}

	@Override
	public void updateServer() {
		// Nothing to do, the menu does the work; needed for Tom's terminal ticker
	}

	/** Pattern Providers on the network, sorted by name, then position. */
	public List<PatternProviderBlockEntity> providers() {
		if (level == null) return List.of();
		List<PatternProviderBlockEntity> out = new ArrayList<>();
		for (CraftingProvider p : network.providers(level, worldPosition)) {
			if (p instanceof PatternProviderBlockEntity pp && level.getBlockEntity(pp.getBlockPos()) == pp) out.add(pp);
		}
		// Resolve every name once, not in each comparison
		java.util.Map<PatternProviderBlockEntity, String> names = new java.util.IdentityHashMap<>();
		for (PatternProviderBlockEntity p : out) names.put(p, providerName(p).getString());
		out.sort(Comparator.comparing((PatternProviderBlockEntity p) -> names.get(p)).thenComparing(PatternProviderBlockEntity::getBlockPos));
		return out;
	}

	/** Anvil name of the provider, else the machine(s) it supplies, else its block name. */
	public static Component providerName(PatternProviderBlockEntity p) {
		return p.listName();
	}

	public boolean isConnected() {
		return level != null && network.isConnected(level, worldPosition);
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		customName.save(out);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		customName.load(in);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new PatternAccessTerminalMenu(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return customName.orElse(Component.translatable("block.tsautocraft.pattern_access_terminal"));
	}

	public boolean stillValid(Player player) {
		return TerminalAccess.stillValid(this, player);
	}

	//region Anvil name

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		customName.apply(components);
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		customName.collect(builder);
	}
	//endregion

	@Override
	public void appendStatus(Consumer<Component> lines) {
		List<PatternProviderBlockEntity> provs = providers();
		int free = 0;
		for (PatternProviderBlockEntity p : provs) {
			int active = PatternProviderBlockEntity.activeSlots(p.getUpgrades());
			for (int i = 0; i < active; i++) if (p.getPatternInventory().getItem(i).isEmpty()) free++;
		}
		lines.accept(Component.translatable("jade.tsautocraft.providers", provs.size()));
		lines.accept(Component.translatable("jade.tsautocraft.free_slots", free));
		if (provs.isEmpty() && !isConnected()) lines.accept(Component.translatable("reason.tsautocraft.no_network"));
	}
}
