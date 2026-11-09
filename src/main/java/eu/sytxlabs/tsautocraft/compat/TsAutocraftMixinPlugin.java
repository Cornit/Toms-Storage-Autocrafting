package eu.sytxlabs.tsautocraft.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import eu.sytxlabs.tsautocraft.platform.Platform;

/** Skips the mixins into Tom's JEI/REI handlers when that recipe viewer is not installed (their targets reference its API). */
public class TsAutocraftMixinPlugin implements IMixinConfigPlugin {
	/** Mixin class simple name -> required mod id. */
	private static final Map<String, String> REQUIRES = Map.of(
			"CraftingTerminalTransferHandlerMixin", "jei",
			"ReiTransferHandlerMixin", "roughlyenoughitems");

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String mod = REQUIRES.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
		return mod == null || Platform.isModLoadedEarly(mod);
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
