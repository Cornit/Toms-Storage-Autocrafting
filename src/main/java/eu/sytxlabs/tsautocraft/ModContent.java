package eu.sytxlabs.tsautocraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MenuType.MenuSupplier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import eu.sytxlabs.tsautocraft.block.AutocraftingTerminalBlock;
import eu.sytxlabs.tsautocraft.block.ConnectorNodeBlock;
import eu.sytxlabs.tsautocraft.block.FramedConnectorNodeBlock;
import eu.sytxlabs.tsautocraft.block.NodeBlock;
import eu.sytxlabs.tsautocraft.block.PatternProviderBlock;
import eu.sytxlabs.tsautocraft.block.PatternTerminalBlock;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.ExporterBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.ImporterBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.PatternTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.RequesterBlockEntity;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu;
import eu.sytxlabs.tsautocraft.menu.IOMenu;
import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu;
import eu.sytxlabs.tsautocraft.menu.RequesterMenu;
import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;

public final class ModContent {
	private static final List<Item> TAB_ITEMS = new ArrayList<>();

	// --- Data component ---
	public static final DataComponentType<PatternData> PATTERN_COMPONENT = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE, TsAutocraft.id("pattern"),
			DataComponentType.<PatternData>builder().persistent(PatternData.CODEC).build());

	// --- Blocks ---
	public static final ConnectorNodeBlock EXPORTER = block("exporter", p -> new ConnectorNodeBlock(p.noOcclusion(), ExporterBlockEntity::new, false));
	public static final FramedConnectorNodeBlock EXPORTER_FRAMED = block("exporter_framed", p -> new FramedConnectorNodeBlock(p.noOcclusion(), ExporterBlockEntity::new, false));
	public static final ConnectorNodeBlock IMPORTER = block("importer", p -> new ConnectorNodeBlock(p.noOcclusion(), ImporterBlockEntity::new, false));
	public static final FramedConnectorNodeBlock IMPORTER_FRAMED = block("importer_framed", p -> new FramedConnectorNodeBlock(p.noOcclusion(), ImporterBlockEntity::new, false));
	public static final PatternProviderBlock PATTERN_PROVIDER = block("pattern_provider", p -> new PatternProviderBlock(p, PatternProviderBlockEntity::new));
	public static final ConnectorNodeBlock PATTERN_PROVIDER_CONNECTOR = block("pattern_provider_connector", p -> new ConnectorNodeBlock(p.noOcclusion(), PatternProviderBlockEntity::new, true));
	public static final FramedConnectorNodeBlock PATTERN_PROVIDER_CONNECTOR_FRAMED = block("pattern_provider_connector_framed", p -> new FramedConnectorNodeBlock(p.noOcclusion(), PatternProviderBlockEntity::new, true));
	/** Blocks that use Tom's painted model (client registers {@code UnbakedPaintedModel} and tint for these). */
	public static final List<Block> FRAMED_BLOCKS = List.of(EXPORTER_FRAMED, IMPORTER_FRAMED, PATTERN_PROVIDER_CONNECTOR_FRAMED);
	public static final PatternTerminalBlock PATTERN_TERMINAL = block("pattern_terminal", p -> new PatternTerminalBlock(p.noOcclusion().lightLevel(s -> 6)));
	public static final AutocraftingTerminalBlock AUTOCRAFTING_TERMINAL = block("autocrafting_terminal", p -> new AutocraftingTerminalBlock(p.noOcclusion().lightLevel(s -> 6)));
	public static final NodeBlock REQUESTER = block("requester", p -> new NodeBlock(p, RequesterBlockEntity::new));

	// --- Items ---
	public static final Item BLANK_PATTERN = item("blank_pattern", Item::new, p -> p.stacksTo(64));
	public static final PatternItem PATTERN = item("pattern", PatternItem::new, p -> p.stacksTo(1));

	// --- Block Entities ---
	public static final BlockEntityType<ExporterBlockEntity> EXPORTER_BE = blockEntity("exporter", ExporterBlockEntity::new, EXPORTER, EXPORTER_FRAMED);
	public static final BlockEntityType<ImporterBlockEntity> IMPORTER_BE = blockEntity("importer", ImporterBlockEntity::new, IMPORTER, IMPORTER_FRAMED);
	public static final BlockEntityType<PatternProviderBlockEntity> PATTERN_PROVIDER_BE = blockEntity("pattern_provider", PatternProviderBlockEntity::new,
			PATTERN_PROVIDER, PATTERN_PROVIDER_CONNECTOR, PATTERN_PROVIDER_CONNECTOR_FRAMED);
	public static final BlockEntityType<PatternTerminalBlockEntity> PATTERN_TERMINAL_BE = blockEntity("pattern_terminal", PatternTerminalBlockEntity::new, PATTERN_TERMINAL);
	public static final BlockEntityType<AutocraftingTerminalBlockEntity> AUTOCRAFTING_TERMINAL_BE = blockEntity("autocrafting_terminal", AutocraftingTerminalBlockEntity::new, AUTOCRAFTING_TERMINAL);
	public static final BlockEntityType<RequesterBlockEntity> REQUESTER_BE = blockEntity("requester", RequesterBlockEntity::new, REQUESTER);

	// --- Menus (client constructor without block entity) ---
	public static final MenuType<IOMenu> EXPORTER_MENU = menu("exporter", (id, inv) -> new IOMenu(ModContent.EXPORTER_MENU, id, inv, null));
	public static final MenuType<IOMenu> IMPORTER_MENU = menu("importer", (id, inv) -> new IOMenu(ModContent.IMPORTER_MENU, id, inv, null));
	public static final MenuType<PatternProviderMenu> PATTERN_PROVIDER_MENU = menu("pattern_provider", PatternProviderMenu::new);
	public static final MenuType<PatternTerminalMenu> PATTERN_TERMINAL_MENU = menu("pattern_terminal", PatternTerminalMenu::new);
	public static final MenuType<AutocraftingTerminalMenu> AUTOCRAFTING_TERMINAL_MENU = menu("autocrafting_terminal", AutocraftingTerminalMenu::new);
	public static final MenuType<RequesterMenu> REQUESTER_MENU = menu("requester", RequesterMenu::new);

	// --- Creative Tab ---
	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, TsAutocraft.id("tab")),
			FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.tsautocraft.tab"))
			.icon(() -> new ItemStack(ModContent.PATTERN_PROVIDER))
			.displayItems((params, out) -> TAB_ITEMS.forEach(out::accept))
			.build());

	private ModContent() {}

	/** Loads the class and thereby all static registrations. */
	public static void init() {}

	private static BlockBehaviour.Properties defaults(BlockBehaviour.Properties p) {
		return p.mapColor(MapColor.METAL).sound(SoundType.COPPER).strength(3);
	}

	private static <B extends Block> B block(String name, Function<BlockBehaviour.Properties, B> factory) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, TsAutocraft.id(name));
		B block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(defaults(BlockBehaviour.Properties.of().setId(key))));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, TsAutocraft.id(name));
		BlockItem item = Registry.register(BuiltInRegistries.ITEM, itemKey, new TooltipBlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		TAB_ITEMS.add(item);
		return block;
	}

	private static <I extends Item> I item(String name, Function<Item.Properties, I> factory, java.util.function.UnaryOperator<Item.Properties> props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, TsAutocraft.id(name));
		I item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.apply(new Item.Properties().setId(key))));
		TAB_ITEMS.add(item);
		return item;
	}

	private static <T extends BlockEntity> BlockEntityType<T> blockEntity(String name, FabricBlockEntityTypeBuilder.Factory<T> factory, Block... blocks) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, TsAutocraft.id(name), FabricBlockEntityTypeBuilder.create(factory, blocks).build());
	}

	private static <M extends AbstractContainerMenu> MenuType<M> menu(String name, MenuSupplier<M> supplier) {
		return Registry.register(BuiltInRegistries.MENU, TsAutocraft.id(name), new MenuType<>(supplier, FeatureFlags.VANILLA_SET));
	}
}
