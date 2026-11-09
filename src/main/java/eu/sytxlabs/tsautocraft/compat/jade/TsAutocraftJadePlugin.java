package eu.sytxlabs.tsautocraft.compat.jade;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.blockentity.StatusInfo;

/**
 * Jade (entrypoint {@code jade}): status lines of our block entities ({@link StatusInfo}),
 * e.g. pattern count and target of a Pattern Provider or the running jobs of an Autocrafting Terminal. The server sends the lines as components.
 */
@snownee.jade.api.WailaPlugin // NeoForge finds Jade plugins by this annotation, Fabric by the entrypoint
public class TsAutocraftJadePlugin implements IWailaPlugin {
	static final Identifier UID = TsAutocraft.id("status");
	private static final String KEY = "tsac_status";

	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(Server.INSTANCE, BlockEntity.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerBlockComponent(Client.INSTANCE, Block.class);
	}

	enum Server implements IServerDataProvider<BlockAccessor> {
		INSTANCE;

		@Override
		public boolean shouldRequestData(BlockAccessor accessor) {
			return accessor.getBlockEntity() instanceof StatusInfo;
		}

		@Override
		public void appendServerData(CompoundTag data, BlockAccessor accessor) {
			if (!(accessor.getBlockEntity() instanceof StatusInfo info)) return;
			var ops = accessor.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
			ListTag list = new ListTag();
			info.appendStatus(c -> ComponentSerialization.CODEC.encodeStart(ops, c).result().ifPresent(list::add));
			data.put(KEY, list);
		}

		@Override
		public Identifier getUid() {
			return UID;
		}
	}

	enum Client implements IBlockComponentProvider {
		INSTANCE;

		@Override
		public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
			CompoundTag data = accessor.getServerData();
			if (!data.contains(KEY)) return;
			var ops = accessor.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
			List<Component> lines = new ArrayList<>();
			for (Tag t : data.getListOrEmpty(KEY)) ComponentSerialization.CODEC.parse(ops, t).result().ifPresent(lines::add);
			lines.forEach(tooltip::add);
		}

		@Override
		public Identifier getUid() {
			return UID;
		}
	}
}
