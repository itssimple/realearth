package se.itssimple.realearth.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.server.level.ServerLevel;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.world.EarthChunkGenerator;

/**
 * Hooks Real Earth into Distant Horizons. Only loaded when Distant Horizons is installed; nothing
 * else in the mod refers to its classes.
 */
public final class DistantHorizonsCompat {
	private DistantHorizonsCompat() {
	}

	public static void init() {
		DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
			@Override
			public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
				IDhApiLevelWrapper level = event.value.levelWrapper;
				if (level.getLevelType() != EDhApiLevelType.SERVER_LEVEL) {
					return;
				}
				if (level.getWrappedMcObject() instanceof ServerLevel server
					&& server.getChunkSource().getGenerator() instanceof EarthChunkGenerator) {
					DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(level, new EarthLodGenerator(level));
					if (result.success) {
						RealEarth.LOGGER.info("Distant Horizons: distant terrain for {} comes straight from Real Earth's data", server.dimension().identifier());
					} else {
						RealEarth.LOGGER.warn("Distant Horizons refused the Real Earth generator for {}: {}", server.dimension().identifier(), result.message);
					}
				}
			}
		});
		RealEarth.LOGGER.info("Distant Horizons {} found; Real Earth will supply its distant terrain", DhApi.getModVersion());
	}
}
