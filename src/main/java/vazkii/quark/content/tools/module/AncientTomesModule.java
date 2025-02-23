package vazkii.quark.content.tools.module;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.event.entity.player.AnvilRepairEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import vazkii.quark.api.IRuneColorProvider;
import vazkii.quark.api.QuarkCapabilities;
import vazkii.quark.base.Quark;
import vazkii.quark.base.handler.MiscUtil;
import vazkii.quark.base.handler.advancement.QuarkAdvancementHandler;
import vazkii.quark.base.handler.advancement.QuarkGenericTrigger;
import vazkii.quark.base.module.LoadModule;
import vazkii.quark.base.module.ModuleCategory;
import vazkii.quark.base.module.ModuleLoader;
import vazkii.quark.base.module.QuarkModule;
import vazkii.quark.base.module.config.Config;
import vazkii.quark.content.tools.item.AncientTomeItem;
import vazkii.quark.content.tools.loot.EnchantTome;
import vazkii.quark.content.world.module.MonsterBoxModule;

@LoadModule(category = ModuleCategory.TOOLS, hasSubscriptions = true)
public class AncientTomesModule extends QuarkModule {

	private static final Object mutex = new Object();

	private static String loot(ResourceLocation lootLoc, int defaultWeight) {
		return lootLoc.toString() + "," + defaultWeight;
	}

	@Config(description = "Format is lootTable,weight. i.e. \"minecraft:chests/stronghold_library,30\"")
	public static List<String> lootTables = Lists.newArrayList(
			loot(BuiltInLootTables.STRONGHOLD_LIBRARY, 30),
			loot(BuiltInLootTables.SIMPLE_DUNGEON, 20),
			loot(BuiltInLootTables.BASTION_TREASURE, 25),
			loot(BuiltInLootTables.WOODLAND_MANSION, 15),
			loot(BuiltInLootTables.NETHER_BRIDGE, 0),
			loot(BuiltInLootTables.UNDERWATER_RUIN_BIG, 0),
			loot(BuiltInLootTables.UNDERWATER_RUIN_SMALL, 0),
			loot(BuiltInLootTables.ANCIENT_CITY, 4),
			loot(MonsterBoxModule.MONSTER_BOX_LOOT_TABLE, 5)
			);

	private static final Object2IntMap<ResourceLocation> lootTableWeights = new Object2IntArrayMap<>();

	@Config public static int itemQuality = 2;

	@Config public static int normalUpgradeCost = 10;
	@Config public static int limitBreakUpgradeCost = 30;

	public static LootItemFunctionType tomeEnchantType;

	@Config(name = "Valid Enchantments")
	public static List<String> enchantNames = generateDefaultEnchantmentList();

	@Config 
	public static boolean overleveledBooksGlowRainbow = true;

	@Config(description = "When enabled, Efficiency VI Diamond and Netherite pickaxes can instamine Deepslate when under Haste 2", flag = "deepslate_tweak")
	public static boolean deepslateTweak = true;

	@Config
	public static boolean deepslateTweakNeedsHaste2 = true;

	@Config(description = "Master Librarians will offer to exchange Ancient Tomes, provided you give them a max-level Enchanted Book of the Tome's enchantment too.")
	public static boolean librariansExchangeAncientTomes = true;

	@Config(description = "Applying a tome will also randomly curse your item")
	public static boolean curseGear = false;

	@Config(description = "Allows combining tomes with normal books")
	public static boolean combineWithBooks = true;

	public static Item ancient_tome;
	public static final List<Enchantment> validEnchants = new ArrayList<>();
	private static boolean initialized = false;

	public static QuarkGenericTrigger overlevelTrigger;
	public static QuarkGenericTrigger instamineDeepslateTrigger;
	
	@Override
	public void register() {
		ancient_tome = new AncientTomeItem(this);

		tomeEnchantType = new LootItemFunctionType(new EnchantTome.Serializer());
		Registry.register(Registry.LOOT_FUNCTION_TYPE, new ResourceLocation(Quark.MOD_ID, "tome_enchant"), tomeEnchantType);
		
		overlevelTrigger = QuarkAdvancementHandler.registerGenericTrigger("overlevel");
		instamineDeepslateTrigger = QuarkAdvancementHandler.registerGenericTrigger("instamine_deepslate");
	}

	@SubscribeEvent
	public void onTradesLoaded(VillagerTradesEvent event) {
		if(event.getType() == VillagerProfession.LIBRARIAN && librariansExchangeAncientTomes) {
			synchronized (mutex) {
				Int2ObjectMap<List<ItemListing>> trades = event.getTrades();
				trades.get(5).add(new ExchangeAncientTomesTrade());
			}
		}
	}

	@Override
	public void configChanged() {
		lootTableWeights.clear();
		for (String table : lootTables) {
			String[] split = table.split(",");
			if (split.length == 2) {
				int weight;
				ResourceLocation loc = new ResourceLocation(split[0]);
				try {
					weight = Integer.parseInt(split[1]);
				} catch (NumberFormatException e) {
					continue;
				}
				if (weight > 0)
					lootTableWeights.put(loc, weight);
			}
		}

		if(initialized)
			setupEnchantList();
	}

	@Override
	public void setup() {
		setupEnchantList();
		setupCursesList();
		initialized = true;
	}

	@SubscribeEvent
	public void onLootTableLoad(LootTableLoadEvent event) {
		ResourceLocation res = event.getName();
		int weight = lootTableWeights.getOrDefault(res, 0);

		if(weight > 0) {
			LootPoolEntryContainer entry = LootItem.lootTableItem(ancient_tome)
					.setWeight(weight)
					.setQuality(itemQuality)
					.apply(() -> new EnchantTome(new LootItemCondition[0]))
					.build();

			MiscUtil.addToLootTable(event.getTable(), entry);
		}
	}

	public static boolean isInitialized() {
		return initialized;
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onAnvilUpdate(AnvilUpdateEvent event) {
		ItemStack left = event.getLeft();
		ItemStack right = event.getRight();
		String name = event.getName();

		if(!left.isEmpty() && !right.isEmpty() ) {
			
			// Apply tome to book or item
			if(right.is(ancient_tome)) {
				if(!combineWithBooks && left.is(Items.ENCHANTED_BOOK))
					return;
				
				Enchantment ench = getTomeEnchantment(right);
				Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(left);

				if(ench != null && enchants.containsKey(ench) && enchants.get(ench) <= ench.getMaxLevel()) {
					int lvl = enchants.get(ench) + 1;
					enchants.put(ench, lvl);

					ItemStack out = left.copy();
					EnchantmentHelper.setEnchantments(enchants, out);
					int cost = lvl > ench.getMaxLevel() ? limitBreakUpgradeCost : normalUpgradeCost;

					if(name != null && !name.isEmpty() && (!out.hasCustomHoverName() || !out.getHoverName().getString().equals(name))) {
						out.setHoverName(Component.literal(name));
						cost++;
					}

					event.setOutput(out);
					event.setCost(cost);
				}
			}

			// Apply overleveled book to item
			else if(combineWithBooks && right.is(Items.ENCHANTED_BOOK)) {
				Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(right);
				Map<Enchantment, Integer> currentEnchants = EnchantmentHelper.getEnchantments(left);
				boolean hasOverLevel = false;
				boolean hasMatching = false;
				for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
					Enchantment enchantment = entry.getKey();
					if(enchantment == null)
						continue;

					int level = entry.getValue();
					if (level > enchantment.getMaxLevel()) {
						hasOverLevel = true;
						if (enchantment.canEnchant(left) || left.is(Items.ENCHANTED_BOOK)) {
							hasMatching = true;
							//remove incompatible enchantments
							for (Iterator<Enchantment> iterator = currentEnchants.keySet().iterator(); iterator.hasNext(); ) {
								Enchantment comparingEnchantment = iterator.next();
								if (comparingEnchantment == enchantment)
									continue;

								if (!comparingEnchantment.isCompatibleWith(enchantment)) {
									iterator.remove();
								}
							}
							currentEnchants.put(enchantment, level);
						}
					} else if (enchantment.canEnchant(left)) {
						boolean compatible = true;
						//don't apply incompatible enchantments
						for (Enchantment comparingEnchantment : currentEnchants.keySet()) {
							if (comparingEnchantment == enchantment)
								continue;

							if (comparingEnchantment != null && !comparingEnchantment.isCompatibleWith(enchantment)) {
								compatible = false;
								break;
							}
						}
						if (compatible) {
							currentEnchants.put(enchantment, level);
						}
					}
				}

				if (hasOverLevel) {
					if (hasMatching) {
						ItemStack out = left.copy();
						EnchantmentHelper.setEnchantments(currentEnchants, out);
						int cost = normalUpgradeCost;

						if(name != null && !name.isEmpty() && (!out.hasCustomHoverName() || !out.getHoverName().getString().equals(name))) {
							out.setHoverName(Component.literal(name));
							cost++;
						}

						event.setOutput(out);
						event.setCost(cost);
					}
				}
			}
		}
	}
	
	@SubscribeEvent
	public void onAnvilUse(AnvilRepairEvent event) {
		ItemStack output = event.getOutput();
		ItemStack right = event.getRight();

		if(curseGear && (right.is(ancient_tome) || event.getLeft().is(ancient_tome))){
			event.getOutput().enchant(curses.get(event.getEntity().level.random.nextInt(curses.size())),1);
		}
		
		if(isOverlevel(output) && (right.getItem() == Items.ENCHANTED_BOOK || right.getItem() == ancient_tome) && event.getEntity() instanceof ServerPlayer sp)
			overlevelTrigger.trigger(sp);
	}

	@SubscribeEvent
	public void onGetSpeed(PlayerEvent.BreakSpeed event) {
		if(deepslateTweak) {
			Player player = event.getEntity();
			ItemStack stack = player.getMainHandItem();
			BlockState state = event.getState();
			
			if(state.is(Blocks.DEEPSLATE) 
					&& EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack) >= 6 
					&& event.getOriginalSpeed() >= 45F
					&& (!deepslateTweakNeedsHaste2 || playerHasHaste2(player))) {
				
				event.setNewSpeed(100F);
				
				if(player instanceof ServerPlayer sp)
					instamineDeepslateTrigger.trigger(sp);
			}
		}
	}
	
	private boolean playerHasHaste2(Player player) {
		MobEffectInstance inst = player.getEffect(MobEffects.DIG_SPEED);
		return inst != null && inst.getAmplifier() > 0;
	}

	private static boolean isOverlevel(ItemStack stack) {
		Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
		for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
			Enchantment enchantment = entry.getKey();
			if (enchantment == null)
				continue;

			int level = entry.getValue();
			if (level > enchantment.getMaxLevel()) {
				return true;
			}
		}

		return false;
	}

	private static final ResourceLocation OVERLEVEL_COLOR_HANDLER = new ResourceLocation(Quark.MOD_ID, "overlevel_rune");

	@SubscribeEvent
	public void attachRuneCapability(AttachCapabilitiesEvent<ItemStack> event) {
		if (event.getObject().getItem() == Items.ENCHANTED_BOOK) {
			IRuneColorProvider provider = new IRuneColorProvider() {
				@Override
				public int getRuneColor(ItemStack stack) {
					if (overleveledBooksGlowRainbow && isOverlevel(stack))
						return 16;
					else
						return -1;
				}
			};

			LazyOptional<IRuneColorProvider> holder = LazyOptional.of(() -> provider);

			event.addCapability(OVERLEVEL_COLOR_HANDLER, new ICapabilityProvider() {
				@Nonnull
				@Override
				public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
					return QuarkCapabilities.RUNE_COLOR.orEmpty(cap, holder);
				}
			});
		}
	}

	public static Rarity shiftRarity(ItemStack itemStack, Rarity returnValue) {
		return ModuleLoader.INSTANCE.isModuleEnabled(AncientTomesModule.class) && overleveledBooksGlowRainbow &&
				itemStack.getItem() == Items.ENCHANTED_BOOK && isOverlevel(itemStack) ? Rarity.EPIC : returnValue;

	}

	private static List<String> generateDefaultEnchantmentList() {
		Enchantment[] enchants = new Enchantment[] {
				Enchantments.FALL_PROTECTION,
				Enchantments.THORNS,
				Enchantments.SHARPNESS,
				Enchantments.SMITE,
				Enchantments.BANE_OF_ARTHROPODS,
				Enchantments.KNOCKBACK,
				Enchantments.FIRE_ASPECT,
				Enchantments.MOB_LOOTING,
				Enchantments.SWEEPING_EDGE,
				Enchantments.BLOCK_EFFICIENCY,
				Enchantments.UNBREAKING,
				Enchantments.BLOCK_FORTUNE,
				Enchantments.POWER_ARROWS,
				Enchantments.PUNCH_ARROWS,
				Enchantments.FISHING_LUCK,
				Enchantments.FISHING_SPEED,
				Enchantments.LOYALTY,
				Enchantments.RIPTIDE,
				Enchantments.IMPALING,
				Enchantments.PIERCING
		};

		List<String> strings = new ArrayList<>();
		for(Enchantment e : enchants) {
			ResourceLocation regname = Registry.ENCHANTMENT.getKey(e);
			if(e != null && regname != null)
				strings.add(regname.toString());
		}

		return strings;
	}

	private void setupEnchantList() {
		MiscUtil.initializeEnchantmentList(enchantNames, validEnchants);
		validEnchants.removeIf((ench) -> ench.getMaxLevel() == 1);
	}

	private final List<Enchantment> curses = new ArrayList<>();

	public void setupCursesList() {
		for (var e : Registry.ENCHANTMENT) {
			if (e.isCurse()) curses.add(e);
		}
	}

	public static Enchantment getTomeEnchantment(ItemStack stack) {
		if (stack.getItem() != ancient_tome)
			return null;

		ListTag list = EnchantedBookItem.getEnchantments(stack);

		for(int i = 0; i < list.size(); ++i) {
			CompoundTag nbt = list.getCompound(i);
			Enchantment enchant = ForgeRegistries.ENCHANTMENTS.getValue(ResourceLocation.tryParse(nbt.getString("id")));
			if (enchant != null)
				return enchant;
		}

		return null;
	}

	private static boolean isAncientTomeOffer(MerchantOffer offer) {
		return offer.getCostA().is(ancient_tome) && offer.getCostB().is(Items.ENCHANTED_BOOK) && offer.getResult().is(ancient_tome);
	}

	public static void moveVillagerItems(MerchantMenu menu, MerchantContainer container, MerchantOffer offer) {
		// Doesn't check if enabled, since this should apply to the trades that have already been generated regardless
		if (isAncientTomeOffer(offer)) {
			if (container.getItem(0).isEmpty() && container.getItem(1).isEmpty()) {
				ItemStack costA = offer.getCostA();
				moveFromInventoryToPaymentSlot(menu, container, offer, 0, costA);
				ItemStack costB = offer.getCostB();
				moveFromInventoryToPaymentSlot(menu, container, offer, 1, costB);
			}
		}
	}

	private static void moveFromInventoryToPaymentSlot(MerchantMenu menu, MerchantContainer container, MerchantOffer offer, int tradeSlot, ItemStack targetStack) {
		menu.moveFromInventoryToPaymentSlot(tradeSlot, targetStack);
		// Do a second pass with a softer match severity, but don't put in books that are the same as the output
		if (container.getItem(tradeSlot).isEmpty() && !targetStack.isEmpty()) {
			for(int slot = 3; slot < 39; ++slot) {
				ItemStack inSlot = menu.slots.get(slot).getItem();
				ItemStack currentStack = container.getItem(tradeSlot);

				if (!ItemStack.isSameItemSameTags(inSlot, offer.getResult()) &&
						!inSlot.isEmpty() && (currentStack.isEmpty() ? offer.isRequiredItem(inSlot, targetStack) :
							ItemStack.isSameItemSameTags(targetStack, inSlot))) {
					int currentCount = currentStack.isEmpty() ? 0 : currentStack.getCount();
					int amountToTake = Math.min(targetStack.getMaxStackSize() - currentCount, inSlot.getCount());
					ItemStack newStack = inSlot.copy();
					int newCount = currentCount + amountToTake;
					inSlot.shrink(amountToTake);
					newStack.setCount(newCount);
					container.setItem(tradeSlot, newStack);
					if (newCount >= targetStack.getMaxStackSize()) {
						break;
					}
				}
			}
		}
	}

	public static boolean matchWildcardEnchantedBook(MerchantOffer offer, ItemStack comparing, ItemStack reference) {
		// Doesn't check if enabled, since this should apply to the trades that have already been generated regardless
		if (isAncientTomeOffer(offer) && comparing.is(Items.ENCHANTED_BOOK) && reference.is(Items.ENCHANTED_BOOK)) {
			Map<Enchantment, Integer> referenceEnchants = EnchantmentHelper.getEnchantments(reference);
			if (referenceEnchants.size() == 1) {
				Enchantment enchantment = referenceEnchants.keySet().iterator().next();
				int level = referenceEnchants.get(enchantment);

				Map<Enchantment, Integer> comparingEnchants = EnchantmentHelper.getEnchantments(comparing);
				for (var entry : comparingEnchants.entrySet()) {
					if (entry.getKey() == enchantment && entry.getValue() >= level) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private class ExchangeAncientTomesTrade implements ItemListing {
		@Nullable
		@Override
		public MerchantOffer getOffer(@Nonnull Entity trader, @Nonnull RandomSource random) {
			if (validEnchants.isEmpty() || !enabled)
				return null;
			Enchantment target = validEnchants.get(random.nextInt(validEnchants.size()));

			ItemStack anyTome = new ItemStack(ancient_tome);
			ItemStack enchantedBook = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(target, target.getMaxLevel()));
			ItemStack outputTome = AncientTomeItem.getEnchantedItemStack(target);
			return new MerchantOffer(anyTome, enchantedBook, outputTome, 3, 3, 0.2F);
		}
	}
}
