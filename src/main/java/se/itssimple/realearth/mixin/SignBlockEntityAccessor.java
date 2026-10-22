package se.itssimple.realearth.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A sign's text and wax, set straight: the setters tell the level the sign changed, and a sign put
 * into a chunk while it is generated has no level yet.
 */
@Mixin(SignBlockEntity.class)
public interface SignBlockEntityAccessor {
	@Accessor("frontText")
	void realearth$setFrontText(SignText text);

	@Accessor("backText")
	void realearth$setBackText(SignText text);

	@Accessor("isWaxed")
	void realearth$setWaxed(boolean waxed);
}
