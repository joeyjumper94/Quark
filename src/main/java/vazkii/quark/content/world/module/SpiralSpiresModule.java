package vazkii.quark.content.world.module;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vazkii.quark.base.block.QuarkBlock;
import vazkii.quark.base.handler.advancement.QuarkAdvancementHandler;
import vazkii.quark.base.handler.advancement.QuarkGenericTrigger;
import vazkii.quark.base.module.LoadModule;
import vazkii.quark.base.module.ModuleCategory;
import vazkii.quark.base.module.QuarkModule;
import vazkii.quark.base.module.config.Config;
import vazkii.quark.base.module.config.Config.Max;
import vazkii.quark.base.module.config.Config.Min;
import vazkii.quark.base.module.config.type.CompoundBiomeConfig;
import vazkii.quark.base.module.config.type.DimensionConfig;
import vazkii.quark.base.world.WorldGenHandler;
import vazkii.quark.base.world.WorldGenWeights;
import vazkii.quark.content.world.block.MyaliteCrystalBlock;
import vazkii.quark.content.world.gen.SpiralSpireGenerator;

@LoadModule(category = ModuleCategory.WORLD, hasSubscriptions = true)
public class SpiralSpiresModule extends QuarkModule {

	@Config
	public static DimensionConfig dimensions = DimensionConfig.end(false);

	@Config
	public static CompoundBiomeConfig biomes = CompoundBiomeConfig.fromBiomeReslocs(false, "minecraft:end_highlands");

	@Config public static int rarity = 200;
	@Config public static int radius = 15;

	@Config(flag = "myalite_viaduct")
	public static boolean enableMyaliteViaducts = true;
	
	@Config
	@Min(2)
	@Max(1024)
	public static int myaliteConduitDistance = 24;

	@Config public static boolean renewableMyalite = true;

	public static Block dusky_myalite;
	public static Block myalite_crystal;

	public static QuarkGenericTrigger useViaductTrigger;
	
	@Override
	public void register() {
		Block.Properties props = Block.Properties.of(Material.STONE, MaterialColor.TERRACOTTA_PURPLE)
				.requiresCorrectToolForDrops()
				.strength(1.5F, 6.0F);
		dusky_myalite = new QuarkBlock("dusky_myalite", this, CreativeModeTab.TAB_BUILDING_BLOCKS, props);

		myalite_crystal = new MyaliteCrystalBlock(this);
		
		useViaductTrigger = QuarkAdvancementHandler.registerGenericTrigger("use_viaduct");
	}

	@Override
	public void setup() {
		WorldGenHandler.addGenerator(this, new SpiralSpireGenerator(dimensions), Decoration.SURFACE_STRUCTURES, WorldGenWeights.SPIRAL_SPIRES);
	}

	@SubscribeEvent
	public void onTeleport(EntityTeleportEvent event) {
		if(!enableMyaliteViaducts)
			return;

		Entity entity = event.getEntity();
		Level world = entity.level;
		BlockPos pos = new BlockPos(event.getTargetX(), event.getTargetY(), event.getTargetZ());

		List<BlockPos> myalite = getAdjacentMyalite(null, world, pos, null);
		if (myalite == null || myalite.isEmpty()) {
			pos = pos.below();
			myalite = getAdjacentMyalite(null, world, pos, null);
		}

		if (myalite != null && !myalite.isEmpty()) {
			BlockPos prev;
			BlockPos cond = pos;

			List<BlockPos> found = new ArrayList<>();
			int moves = 0;
			do {
				prev = cond;
				cond = myalite.get(world.random.nextInt(myalite.size()));
				found.add(cond);
				myalite = getAdjacentMyalite(found, world, cond, null);

				moves++;
				if (myalite == null || moves > myaliteConduitDistance)
					return;
			} while (!myalite.isEmpty());


			BlockPos test = cond.offset(cond.getX() - prev.getX(), cond.getY() - prev.getY(), cond.getZ() - prev.getZ());

			find:
			if (!world.getBlockState(test).isAir()) {
				for (Direction d : Direction.values()) {
					test = cond.relative(d);
					if (world.getBlockState(test).isAir()) {
						if (d.getAxis() == Axis.Y)
							test = test.relative(d);

						break find;
					}
				}

				return;
			}

			event.setTargetX(test.getX() + 0.5);
			event.setTargetY(test.getY() + 0.5);
			event.setTargetZ(test.getZ() + 0.5);
			
			if(event.getEntity() instanceof ServerPlayer sp)
				useViaductTrigger.trigger(sp);

			if (world instanceof ServerLevel sworld) {
				for (BlockPos f : found)
					sworld.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, myalite_crystal.defaultBlockState()), f.getX() + 0.5, f.getY() + 0.5, f.getZ() + 0.5, 30, 0.25, 0.25, 0.25, 0);
			}
		}
	}

	private static List<BlockPos> getAdjacentMyalite(List<BlockPos> found, Level world, BlockPos pos, Direction ignore) {
		List<BlockPos> ret = new ArrayList<>(6);
		List<BlockPos> collisions = new ArrayList<>();

		for(Direction d : Direction.values())
			if(d != ignore) {
				BlockPos off = pos.relative(d);
				if(world.getBlockState(off).getBlock() == myalite_crystal) {
					if(found != null && found.contains(off))
						collisions.add(off);
					else ret.add(off);
				}
			}

		if(ret.isEmpty() && collisions.size() > 1)
			return null;

		return ret;
	}

}
