package vazkii.quark.content.tweaks.module;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vazkii.arl.util.ItemNBTHelper;
import vazkii.quark.base.handler.advancement.QuarkAdvancementHandler;
import vazkii.quark.base.handler.advancement.QuarkGenericTrigger;
import vazkii.quark.base.module.LoadModule;
import vazkii.quark.base.module.ModuleCategory;
import vazkii.quark.base.module.QuarkModule;

@LoadModule(category = ModuleCategory.TWEAKS, hasSubscriptions = true)
public class SnowGolemPlayerHeadsModule extends QuarkModule {

	public static QuarkGenericTrigger getOwnHeadTrigger;
	
	@Override
	public void register() {
		getOwnHeadTrigger = QuarkAdvancementHandler.registerGenericTrigger("own_head");
	}
	
	@SubscribeEvent
	public void onDrops(LivingDropsEvent event) {
		Entity e = event.getEntity();

		if(e.hasCustomName() && e instanceof SnowGolem snowman && event.getSource().getEntity() != null && event.getSource().getEntity() instanceof Witch) {
			if(snowman.hasPumpkin()) {
				ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
				String name = e.getCustomName().getString();
				ItemNBTHelper.setString(stack, "SkullOwner", name);
				Vec3 pos = e.position();
				event.getDrops().add(new ItemEntity(e.getCommandSenderWorld(), pos.x, pos.y, pos.z, stack));
				
				for(Player player : e.getLevel().players()) {
					String pname = player.getName().getString();
					if(pname.equals(name) && player instanceof ServerPlayer sp && player.distanceTo(snowman) < 16F)
						getOwnHeadTrigger.trigger(sp);
				}
			}
		}
	}

}
