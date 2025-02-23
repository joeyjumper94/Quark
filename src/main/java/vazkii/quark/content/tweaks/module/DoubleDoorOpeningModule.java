package vazkii.quark.content.tweaks.module;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.minecraftforge.eventbus.api.Event.Result;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vazkii.quark.base.Quark;
import vazkii.quark.base.module.LoadModule;
import vazkii.quark.base.module.ModuleCategory;
import vazkii.quark.base.module.ModuleLoader;
import vazkii.quark.base.module.QuarkModule;
import vazkii.quark.base.module.config.Config;
import vazkii.quark.base.network.QuarkNetwork;
import vazkii.quark.base.network.message.DoubleDoorMessage;
import vazkii.quark.integration.claim.IClaimIntegration;

@LoadModule(category = ModuleCategory.TWEAKS, hasSubscriptions = true, subscribeOn = Dist.CLIENT, antiOverlap = "utilitix")
public class DoubleDoorOpeningModule extends QuarkModule {

	@Config public static boolean enableDoors = true;
	@Config public static boolean enableFenceGates = true;
	
	public static TagKey<Block> nonDoubleDoorTag;
	private static boolean handling = false;
	
	@Override
	public void setup() {
		nonDoubleDoorTag = BlockTags.create(new ResourceLocation(Quark.MOD_ID, "non_double_door"));
	}
	
	@SubscribeEvent(priority = EventPriority.LOW)
	public void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
		Player player = event.getEntity();
		if(!event.getLevel().isClientSide || player.isDiscrete() || event.isCanceled() || event.getResult() == Result.DENY || event.getUseBlock() == Result.DENY || handling)
			return;

		Level world = event.getLevel();
		BlockPos pos = event.getPos();

		if(!IClaimIntegration.INSTANCE.canInteract(player, pos))
			return;

		handling = true;
		boolean opened = openBlock(world, player, pos);
		handling = false;
		
		if(opened)
			QuarkNetwork.sendToServer(new DoubleDoorMessage(pos));
	}

	public static boolean openBlock(Level world, Player player, BlockPos pos) {
		if(!ModuleLoader.INSTANCE.isModuleEnabled(DoubleDoorOpeningModule.class) || world == null)
			return false;

		BlockState state = world.getBlockState(pos);
		if(state.is(nonDoubleDoorTag))
			return false;
		
		if(enableDoors && state.getBlock() instanceof DoorBlock)
			return openDoor(pos, world, player, state);
		
		if(enableFenceGates && state.getBlock() instanceof FenceGateBlock)
			return openFenceGate(pos, world, player, state);

		return false;
	}
	
	private static boolean openDoor(BlockPos pos, Level level, Player player, BlockState state) {
		Direction direction = state.getValue(HorizontalDirectionalBlock.FACING);
		boolean isOpen = state.getValue(BlockStateProperties.OPEN);
		DoorHingeSide isMirrored = state.getValue(DoorBlock.HINGE);

		BlockPos mirrorPos = pos.relative(isMirrored == DoorHingeSide.RIGHT ? direction.getCounterClockWise() : direction.getClockWise());
		BlockPos doorPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? mirrorPos : mirrorPos.below();

		return tryOpen(level, player, state, doorPos, direction, isOpen, test -> test.getValue(DoorBlock.HINGE) != isMirrored);
	}
	
	private static boolean openFenceGate(BlockPos pos, Level level, Player player, BlockState state) {
		Direction direction = state.getValue(FenceGateBlock.FACING);
		boolean isOpen = state.getValue(BlockStateProperties.OPEN);
		
		if(tryOpen(level, player, state, pos.below(), direction, isOpen, Predicates.alwaysTrue()))
			return true;
		
		return tryOpen(level, player, state, pos.above(), direction, isOpen, Predicates.alwaysTrue());
	}
	
	private static boolean tryOpen(Level level, Player player, BlockState state, BlockPos otherPos, Direction direction, boolean isOpen, Predicate<BlockState> pred) {
		BlockState other = level.getBlockState(otherPos);
		if(state.getMaterial() != Material.METAL && other.getBlock() == state.getBlock() && other.getValue(HorizontalDirectionalBlock.FACING) == direction && other.getValue(BlockStateProperties.OPEN) == isOpen && pred.apply(other)) {
			BlockHitResult res = new BlockHitResult(new Vec3(otherPos.getX() + 0.5, otherPos.getY() + 0.5, otherPos.getZ() + 0.5), direction, otherPos, false);

			if(res.getType() == HitResult.Type.BLOCK) {
				RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, otherPos, res);
				boolean eventRes = MinecraftForge.EVENT_BUS.post(event);
				
				if(!eventRes) {
					InteractionResult interaction = other.use(level, player, InteractionHand.MAIN_HAND, res);
					return interaction != InteractionResult.PASS;
				}
			}
		}
		
		return false;
	}

}
