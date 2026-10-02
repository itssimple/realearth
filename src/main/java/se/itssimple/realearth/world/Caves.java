package se.itssimple.realearth.world;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Vanilla's own cave system -- cheese caverns, spaghetti and noodle tunnels, pillars, and the
 * entrances that open them to the surface -- measured from the real ground instead of from vanilla's
 * terrain.
 *
 * Built from the very density functions vanilla registers (overworld/caves/*) and composed the way
 * NoiseRouterData composes the overworld, then wired to this world's seeded noises exactly as
 * RandomState wires vanilla's. The one substitution is vanilla's terrain shape ("sloped cheese"),
 * which here is depth below the local surface. Everything is evaluated at a VIRTUAL vanilla height,
 * 64 minus that depth, so a cave system that sits between Y 64 and Y -64 in a vanilla plains world
 * sits between the real ground and 128 m beneath it on Earth.
 */
public final class Caves {
	/** Vanilla's surface, which the virtual height is measured down from. */
	public static final int VANILLA_SURFACE = 64;
	/** Deepest caves reach, as in vanilla: Y -64 under a surface at 64. */
	public static final int MAX_DEPTH = 128;
	/** Below virtual Y -54, vanilla's cave air is lava. */
	public static final int LAVA_BELOW_VIRTUAL_Y = -54;

	/** How quickly vanilla-like terrain density grows with depth. Puts the underground band ~26 m down. */
	private static final double DENSITY_PER_METRE = 0.06;

	private static final Map<RandomState, DensityFunction> BY_STATE = Collections.synchronizedMap(new WeakHashMap<>());

	private Caves() {
	}

	/** Cave density for this world: below zero is cave. Evaluate at (x, virtual y, z). */
	public static DensityFunction forState(RandomState state, RegistryAccess registries) {
		return BY_STATE.computeIfAbsent(state, s -> build(s, registries));
	}

	private static DensityFunction fn(HolderGetter<DensityFunction> functions, String path) {
		ResourceKey<DensityFunction> key = ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.withDefaultNamespace(path));
		return new DensityFunctions.HolderHolder(functions.getOrThrow(key));
	}

	private static DensityFunction build(RandomState state, RegistryAccess registries) {
		HolderGetter<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);

		// Stand-in for vanilla's terrain density: zero at the surface, growing with depth.
		int deep = VANILLA_SURFACE - 4000;
		DensityFunction slopedCheese = DensityFunctions.yClampedGradient(deep, VANILLA_SURFACE, (VANILLA_SURFACE - deep) * DENSITY_PER_METRE, 0.0);

		DensityFunction entrances = fn(functions, "overworld/caves/entrances");
		DensityFunction spaghetti2D = fn(functions, "overworld/caves/spaghetti_2d");
		DensityFunction roughness = fn(functions, "overworld/caves/spaghetti_roughness_function");
		DensityFunction pillarsRaw = fn(functions, "overworld/caves/pillars");
		DensityFunction noodle = fn(functions, "overworld/caves/noodle");

		// NoiseRouterData.underground(), verbatim apart from slopedCheese.
		DensityFunction layer = DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_LAYER), 8.0);
		DensityFunction layerizedCaverns = DensityFunctions.mul(DensityFunctions.constant(4.0), layer.square());
		DensityFunction cheese = DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666);
		DensityFunction solidifiedCheese = DensityFunctions.add(
			DensityFunctions.add(DensityFunctions.constant(0.27), cheese).clamp(-1.0, 1.0),
			DensityFunctions.add(DensityFunctions.constant(1.5), DensityFunctions.mul(DensityFunctions.constant(-0.64), slopedCheese)).clamp(0.0, 0.5)
		);
		DensityFunction baseCave = DensityFunctions.add(layerizedCaverns, solidifiedCheese);
		DensityFunction subtractions = DensityFunctions.min(
			DensityFunctions.min(baseCave, entrances),
			DensityFunctions.add(spaghetti2D, roughness)
		);
		DensityFunction pillars = DensityFunctions.rangeChoice(pillarsRaw, -1000000.0, 0.03, DensityFunctions.constant(-1000000.0), pillarsRaw);
		DensityFunction underground = DensityFunctions.max(subtractions, pillars);

		// NoiseRouterData.overworld(): near the surface only entrances cut through; deeper, everything.
		DensityFunction surfaceWithEntrances = DensityFunctions.min(slopedCheese, DensityFunctions.mul(DensityFunctions.constant(5.0), entrances));
		DensityFunction caves = DensityFunctions.rangeChoice(slopedCheese, -1000000.0, 1.5625, surfaceWithEntrances, underground);
		DensityFunction full = DensityFunctions.min(DensityFunctions.mul(caves, DensityFunctions.constant(0.64)).squeeze(), noodle);

		return full.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction input) {
				return input;
			}

			@Override
			public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
				return new DensityFunction.NoiseHolder(noise.noiseData(), state.getOrCreateNoise(noise.noiseData().unwrapKey().orElseThrow()));
			}
		});
	}
}
