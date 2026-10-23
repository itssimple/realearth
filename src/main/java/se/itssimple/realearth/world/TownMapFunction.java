package se.itssimple.realearth.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * {@code realearth:town_map}: turns an empty map into a map of the town round the chest it is found in,
 * centred on it, with its land already sketched in and a mark where the chest stands, and named after
 * the town. Worked out when the chest is opened, so with Lootr each player gets a map of their own.
 */
public final class TownMapFunction extends LootItemConditionalFunction {
	public static final MapCodec<TownMapFunction> CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).apply(i, TownMapFunction::new));
	/** A town's map: two blocks to a pixel, 256 blocks across. */
	private static final byte SCALE = 1;

	private TownMapFunction(List<LootItemCondition> predicates) {
		super(predicates);
	}

	@Override
	public MapCodec<TownMapFunction> codec() {
		return CODEC;
	}

	@Override
	public Set<ContextKey<?>> getReferencedContextParams() {
		return Set.of(LootContextParams.ORIGIN);
	}

	@Override
	protected ItemStack run(ItemStack stack, LootContext context) {
		Vec3 origin = context.getOptionalParameter(LootContextParams.ORIGIN);
		if (!stack.is(Items.MAP) || origin == null) {
			return stack;
		}
		ServerLevel level = context.getLevel();
		BlockPos at = BlockPos.containing(origin);
		ItemStack map = MapItem.create(level, at.getX(), at.getZ(), SCALE, true, true);
		MapItem.renderBiomePreviewMap(level, map);
		MapItemSavedData.addTargetDecoration(map, at, "realearth:here", MapDecorationTypes.TARGET_POINT);
		TerrainField field = RealEarth.terrain();
		Places.Here here = field == null || !RealEarth.isEarth(level) ? null : Places.at(field, origin.x, origin.z);
		if (here != null && here.town() != null) {
			String name = here.district() != null ? here.district() + ", " + here.town() : here.town();
			map.set(DataComponents.ITEM_NAME, Component.literal("Map of " + name));
		}
		return map;
	}
}
