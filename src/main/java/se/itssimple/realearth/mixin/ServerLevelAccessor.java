package se.itssimple.realearth.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level's entity storage, so repair can drop the saved entities of a chunk it regenerates. */
@Mixin(ServerLevel.class)
public interface ServerLevelAccessor {
	@Accessor("entityManager")
	PersistentEntitySectionManager<Entity> realearth$entityManager();
}
