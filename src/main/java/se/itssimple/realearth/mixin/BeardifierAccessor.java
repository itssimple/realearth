package se.itssimple.realearth.mixin;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The box a chunk's structures want the terrain shaped in, so only that box is evaluated. */
@Mixin(Beardifier.class)
public interface BeardifierAccessor {
	@Accessor("affectedBox")
	@Nullable BoundingBox realearth$affectedBox();
}
