package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import se.itssimple.realearth.RealEarth;

/**
 * Real Earth's own worldgen entries (the Earth dimension, its type, the world type, the town
 * villages) count as stable. Minecraft marks everything from a mod's data pack "experimental", which
 * would put an "experimental settings" warning in front of every Real Earth world, on every load.
 * Both the entry's own lifecycle and the registry's are affected, so the entry is changed as it is
 * registered.
 */
@Mixin(MappedRegistry.class)
public abstract class RegistryLifecycleMixin {
	@ModifyVariable(method = "register", at = @At("HEAD"), argsOnly = true)
	private RegistrationInfo realearth$stableOwnEntries(RegistrationInfo info, @Local(argsOnly = true) ResourceKey<?> key) {
		if (RealEarth.MOD_ID.equals(key.identifier().getNamespace()) && info.lifecycle() != Lifecycle.stable()) {
			return new RegistrationInfo(info.knownPackInfo(), Lifecycle.stable());
		}
		return info;
	}
}
