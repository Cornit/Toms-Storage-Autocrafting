package eu.sytxlabs.tsautocraft.job;

import java.util.List;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.inventory.IInventoryAccess;

import eu.sytxlabs.tsautocraft.api.CraftingProvider;
import eu.sytxlabs.tsautocraft.network.NetworkLink;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;

public final class JobRunner {
	public static final int INTERVAL = 2;
	private long time = Long.MIN_VALUE;
	private IInventoryAccess net;
	private List<CraftingProvider> providers;
	private Reservations res;

	/** Is this a tick in which the owner should {@linkplain JobScheduler#submit submit} its jobs? */
	public static boolean due(Level level) {
		return level.getGameTime() % INTERVAL == 0;
	}

	/** @return true if the job changed (progress or state), so the owner should be saved */
	public boolean tick(BlockEntity owner, NetworkLink link, CraftingJob job) {
		Level level = owner.getLevel();
		if (level == null) {
			return false;
		}
		long t = level.getGameTime();
		if (t != time) {
			time = t;
			net = link.access(level, owner.getBlockPos());
			providers = link.providers(level, owner.getBlockPos());
			res = TerminalCrafting.reservations(level, owner.getBlockPos());
		}
		long before = job.doneCrafts();
		CraftingJob.State st = job.state();
		job.tick(level, net, providers, INTERVAL, res);
		boolean changed = before != job.doneCrafts() || st != job.state();
		if (changed) owner.setChanged();
		return changed;
	}
}
