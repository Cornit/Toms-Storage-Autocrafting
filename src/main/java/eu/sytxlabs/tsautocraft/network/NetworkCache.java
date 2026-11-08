package eu.sytxlabs.tsautocraft.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.block.entity.IInventoryConnector;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventoryCableNetwork;

import eu.sytxlabs.tsautocraft.api.CraftingProvider;
import eu.sytxlabs.tsautocraft.api.JobOwner;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.Reservations;

/**
 * One shared view of a cable network for all our blocks on it: node block entities, connector, providers (sorted by
 * priority), job owners and the running jobs. Before, every block walked the network on its own.
 * <p>
 * Keyed by the node set Tom's {@link InventoryCableNetwork} returns: Tom builds a new set whenever the network changes,
 * so a snapshot is reused until then, at most {@link #MAX_AGE} ticks (chunk loading does not change the set).
 * {@link #invalidate()} drops all snapshots (placement, priority change); the job list is rebuilt once per tick and
 * whenever a job starts ({@link #jobsChanged()}).
 */
public final class NetworkCache {
	private static final int MAX_AGE = 20;
	private static final Map<Level, Map<Collection<BlockPos>, Snapshot>> LEVELS = new WeakHashMap<>();
	private static int epoch, jobsEpoch, patternEpoch;

	private NetworkCache() {}

	/** Something changed that a snapshot cannot see (new block, provider priority): rebuild on the next use. */
	public static void invalidate() {
		epoch++;
	}

	/** Patterns of a provider changed: craftable lists are rebuilt. */
	public static void patternsChanged() {
		patternEpoch++;
	}

	/** A job started: only the job lists are rebuilt. */
	public static void jobsChanged() {
		jobsEpoch++;
	}

	public static Snapshot of(Level level, BlockPos pos) {
		Collection<BlockPos> nodes = InventoryCableNetwork.getNetwork(level).getNetworkNodes(pos);
		Map<Collection<BlockPos>, Snapshot> map = LEVELS.computeIfAbsent(level, _ -> new IdentityHashMap<>());
		long now = level.getGameTime();
		Snapshot s = map.get(nodes);
		if (s != null && s.epoch == epoch && now >= s.built && now - s.built < MAX_AGE) return s;
		// Old networks (sets Tom has replaced) would stay forever otherwise
		if (map.size() > 32) map.values().removeIf(o -> now < o.built || now - o.built >= MAX_AGE);
		s = new Snapshot(level, nodes, now);
		map.put(nodes, s);
		return s;
	}

	public static final class Snapshot {
		private final long built;
		private final int epoch;
		private final List<BlockEntity> nodes;
		private final IInventoryConnector connector;
		private final List<CraftingProvider> providers;
		private final List<JobOwner> owners;
		private Set<Object> storages;
		private List<CraftingJob> jobs;
		private Reservations reservations;
		private List<net.minecraft.world.item.ItemStack> craftables;
		private int craftablesEpoch;
		private long jobsTime = Long.MIN_VALUE;
		private int jobsEpoch;

		private Snapshot(Level level, Collection<BlockPos> positions, long now) {
			built = now;
			epoch = NetworkCache.epoch;
			List<BlockEntity> n = new ArrayList<>();
			List<CraftingProvider> p = new ArrayList<>();
			List<JobOwner> o = new ArrayList<>();
			IInventoryConnector c = null;
			for (BlockPos pos : positions) {
				if (!level.isLoaded(pos)) continue;
				BlockEntity be = level.getBlockEntity(pos);
				if (be == null) continue;
				n.add(be);
				if (c == null && be instanceof IInventoryConnector ic && ic.hasConnectedInventories()) c = ic;
				if (be instanceof CraftingProvider cp) p.add(cp);
				if (be instanceof JobOwner jo) o.add(jo);
			}
			p.sort(java.util.Comparator.comparingInt(CraftingProvider::providerPriority).reversed());
			nodes = Collections.unmodifiableList(n);
			connector = c;
			providers = Collections.unmodifiableList(p);
			owners = Collections.unmodifiableList(o);
		}

		/** Node block entities (may contain removed ones until the next rebuild: check {@link BlockEntity#isRemoved()}). */
		public List<BlockEntity> nodes() {
			return nodes;
		}

		/** Inventory Connector with storages, or null. */
		public IInventoryConnector connector() {
			IInventoryConnector c = connector;
			return c != null && !(c instanceof BlockEntity be && be.isRemoved()) && c.hasConnectedInventories() ? c : null;
		}

		/** Crafting providers, highest priority first (stable). Read only. */
		public List<CraftingProvider> providers() {
			for (CraftingProvider p : providers) {
				if (p instanceof BlockEntity be && be.isRemoved()) return providers.stream().filter(x -> !(x instanceof BlockEntity b && b.isRemoved())).toList();
			}
			return providers;
		}

		public List<JobOwner> owners() {
			for (JobOwner o : owners) {
				if (o instanceof BlockEntity be && be.isRemoved()) return owners.stream().filter(x -> !(x instanceof BlockEntity b && b.isRemoved())).toList();
			}
			return owners;
		}

		/** Platform handlers of the connector's inventories (identity), see {@link NetworkLink#isNetworkStorage}. */
		Set<Object> storages() {
			if (storages == null) {
				Set<Object> s = Collections.newSetFromMap(new IdentityHashMap<>());
				IInventoryConnector c = connector();
				if (c != null) for (IInventoryAccess a : c.getConnectedInventories()) s.add(a.<Object>getPlatformHandler());
				storages = s;
			}
			return storages;
		}

		/** Active jobs of all owners, rebuilt once per tick and when a job starts. */
		public List<CraftingJob> jobs(Level level) {
			long now = level.getGameTime();
			if (jobs == null || jobsTime != now || jobsEpoch != NetworkCache.jobsEpoch) {
				List<CraftingJob> l = new ArrayList<>();
				for (JobOwner o : owners()) l.addAll(o.activeJobs());
				jobs = l;
				reservations = null;
				jobsTime = now;
				jobsEpoch = NetworkCache.jobsEpoch;
			}
			return jobs;
		}

		/** Craftable outputs of the network (read only), rebuilt only when patterns changed. */
		public List<net.minecraft.world.item.ItemStack> craftables() {
			if (craftables == null || craftablesEpoch != NetworkCache.patternEpoch) {
				craftables = List.copyOf(CraftingJob.craftables(providers()));
				craftablesEpoch = NetworkCache.patternEpoch;
			}
			return craftables;
		}

		/** What the running jobs have reserved, read live from the jobs (stays exact while they dispatch). */
		public Reservations reservations(Level level) {
			List<CraftingJob> j = jobs(level);
			if (reservations == null) reservations = Reservations.live(j);
			return reservations;
		}
	}
}
