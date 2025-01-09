package nl.aurorion.blockregen.preset;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.google.common.base.Strings;
import com.linecorp.conditional.Condition;
import lombok.Getter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.BlockRegenPluginImpl;
import nl.aurorion.blockregen.ParseUtil;
import nl.aurorion.blockregen.api.BlockRegenPlugin;
import nl.aurorion.blockregen.configuration.LoadResult;
import nl.aurorion.blockregen.configuration.ParseException;
import nl.aurorion.blockregen.drop.ItemProvider;
import nl.aurorion.blockregen.event.struct.EventBossBar;
import nl.aurorion.blockregen.event.struct.PresetEvent;
import nl.aurorion.blockregen.preset.condition.ConditionRelation;
import nl.aurorion.blockregen.preset.condition.Conditions;
import nl.aurorion.blockregen.preset.condition.GenericConditionProvider;
import nl.aurorion.blockregen.preset.drop.*;
import nl.aurorion.blockregen.preset.material.TargetMaterial;
import nl.aurorion.blockregen.region.struct.RegenerationArea;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Log
public class PresetManager {

    private final BlockRegenPlugin plugin;

    private final Map<String, BlockPreset> presets = new HashMap<>();

    @Getter
    private final GenericConditionProvider conditions = GenericConditionProvider.empty();

    public PresetManager(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    public BlockPreset getPreset(@Nullable String name) {
        return presets.get(name);
    }

    @Nullable
    public BlockPreset getPreset(@NotNull Block block) {
        for (BlockPreset preset : this.presets.values()) {
            if (preset.getTargetMaterial().matches(block)) {
                return preset;
            }
        }
        return null;
    }

    @Nullable
    public BlockPreset getPreset(@NotNull Block block, @Nullable RegenerationArea region) {
        if (region == null) {
            return getPreset(block);
        }

        for (BlockPreset preset : this.presets.values()) {
            if (preset.getTargetMaterial().matches(block) && region.hasPreset(preset.getName())) {
                return preset;
            }
        }
        return null;
    }

    public Map<String, BlockPreset> getPresets() {
        return Collections.unmodifiableMap(presets);
    }

    public void load() {
        presets.clear();

        // Clear all events before loading.
        plugin.getEventManager().clearEvents();

        ConfigurationSection blocks = plugin.getFiles().getBlockList().getFileConfiguration()
                .getConfigurationSection("Blocks");

        if (blocks == null) {
            return;
        }

        for (String key : blocks.getKeys(false)) {
            try {
                load(key);
            } catch (Exception e) {
                log.log(Level.WARNING, String.format("Could not load preset '%s': %s", key, e.getMessage()), e);
                e.printStackTrace();
            }
        }

        log.info("Loaded " + presets.size() + " block preset(s)...");
        log.info("Added " + plugin.getEventManager().getLoadedEvents().size() + " event(s)...");
    }

    /**
     * @throws IllegalArgumentException If parsing fails.
     */
    public void load(String name) {
        FileConfiguration file = plugin.getFiles().getBlockList().getFileConfiguration();

        ConfigurationSection section = file.getConfigurationSection("Blocks." + name);

        if (section == null) {
            return;
        }

        BlockPreset preset = new BlockPreset(name);

        String targetMaterialInput = section.getString("target-material", name);

        // Target material
        TargetMaterial targetMaterial = this.plugin.getMaterialManager().parseTargetMaterial(targetMaterialInput);
        preset.setTargetMaterial(targetMaterial);
        log.fine(() -> String.format("target-material: %s", preset.getTargetMaterial()));

        // Replace material
        String replaceMaterial = section.getString("replace-block");

        if (!Strings.isNullOrEmpty(replaceMaterial)) {
            try {
                preset.setReplaceMaterial(this.plugin.getMaterialManager().parsePlacementMaterial(replaceMaterial));
            } catch (IllegalArgumentException e) {
                log.warning("Dynamic material ( " + replaceMaterial + " ) in 'replace-block' for " + name + " is invalid: " + e.getMessage());
            }
        }
        log.fine(() -> String.format("replace-material: %s", preset.getReplaceMaterial()));

        // Regenerate into
        String regenerateIntoInput = section.getString("regenerate-into");

        if (!Strings.isNullOrEmpty(regenerateIntoInput)) {
            try {
                preset.setRegenMaterial(this.plugin.getMaterialManager().parsePlacementMaterial(regenerateIntoInput));
            } catch (IllegalArgumentException e) {
                log.warning("Dynamic material ( " + replaceMaterial + " ) in 'regenerate-into' for " + name + " is invalid: " + e.getMessage());
            }
        }
        log.fine(() -> String.format("regenerate-into: %s", preset.getRegenMaterial()));

        LoadResult.tryLoad(section, "regen-delay", NumberValue.Parser::load)
                .ifEmpty(NumberValue.fixed(3))
                .apply(preset::setDelay);

        // Natural break
        preset.setNaturalBreak(section.getBoolean("natural-break", true));

        preset.setDisablePhysics(section.getBoolean("disable-physics", false));

        // Apply fortune
        preset.setApplyFortune(section.getBoolean("apply-fortune", true));

        // Drop naturally
        preset.setDropNaturally(section.getBoolean("drop-naturally", true));

        // Handle crops
        preset.setHandleCrops(section.getBoolean("handle-crops", true));

        // Solid ground
        preset.setCheckSolidGround(section.getBoolean("check-solid-ground", true));

        // Regenerate whole
        preset.setRegenerateWhole(section.getBoolean("regenerate-whole", false));

        // Block Break Sound
        String sound = section.getString("sound");

        if (!Strings.isNullOrEmpty(sound)) {
            Optional<XSound> xSound = XSound.of(sound);
            if (!xSound.isPresent()) {
                log.warning("Sound '" + sound + "' in preset " + name + " is invalid.");
            } else {
                preset.setSound(xSound.get());
            }
        }

        // Particle
        String particleName = section.getString("particles");
        if (!Strings.isNullOrEmpty(particleName)) {
            preset.setParticle(particleName);
        }

        String regenParticle = section.getString("regeneration-particles");
        if (!Strings.isNullOrEmpty(regenParticle)) {
            preset.setRegenerationParticle(regenParticle);
        }

        // Conditions
        PresetConditions conditions = new PresetConditions();
        // Tools
        String toolsRequired = section.getString("tool-required");
        if (!Strings.isNullOrEmpty(toolsRequired)) {
            conditions.setToolsRequired(toolsRequired);
        }

        // Enchants
        String enchantsRequired = section.getString("enchant-required");
        if (!Strings.isNullOrEmpty(enchantsRequired)) {
            conditions.setEnchantsRequired(enchantsRequired);
        }

        // Jobs
        if (plugin.getCompatibilityManager().getJobs().isLoaded()) {
            String jobsRequired = section.getString("jobs-check");
            if (!Strings.isNullOrEmpty(jobsRequired)) {
                conditions.setJobsRequired(jobsRequired);
            }
        }
        preset.setConditions(conditions);

        Condition condition = this.loadConditions(section, "conditions");
        preset.setCondition(condition);

        // Rewards
        PresetRewards rewards = loadRewards(section, preset);
        preset.setRewards(rewards);

        try {
            PresetEvent event = loadEvent(section.getConfigurationSection("event"), preset);
            if (event != null) {
                plugin.getEventManager().addEvent(event);
            }
        } catch (IllegalStateException e) {
            log.warning("Failed to load event for preset " + preset.getName() + ": " + e.getMessage());
        }

        presets.put(name, preset);
        log.fine(() -> "Loaded preset " + preset);
    }

    /**
     * @throws ParseException If the parsing fails.
     * */
    @NotNull
    private Condition loadConditions(@NotNull ConfigurationSection root, @NotNull String key) {
        Object node = root.get(key);
        if (node == null) {
            return Condition.trueCondition();
        }
        return Conditions.fromNodeMultiple(node, ConditionRelation.AND, this.conditions);
    }

    /**
     * @throws IllegalStateException If the parsing fails.
     */
    private PresetEvent loadEvent(ConfigurationSection section, BlockPreset preset) {
        if (section == null) {
            return null;
        }

        PresetEvent event = new PresetEvent(preset.getName());

        String displayName = section.getString("event-name");
        if (displayName == null) {
            throw new IllegalStateException("Event name is missing.");
        }

        event.setDisplayName(displayName);

        event.setDoubleDrops(section.getBoolean("double-drops", false));
        event.setDoubleExperience(section.getBoolean("double-exp", false));

        if (BlockRegenPluginImpl.getInstance().getVersionManager().isCurrentAbove("1.8", false)) {
            event.setBossBar(EventBossBar.load(section.getConfigurationSection("bossbar"), "&eEvent &6" + displayName + " &eis active!"));
        }

        // Load legacy custom item option
        event.setItem(loadDrop(section.getConfigurationSection("custom-item"), preset));

        LoadResult.tryLoad(section, "custom-item.rarity", NumberValue.Parser::load)
                .ifNotFull(NumberValue.fixed(1))
                .apply(event::setItemRarity);

        event.setRewards(loadRewards(section, preset));

        return event;
    }

    /**
     * @throws IllegalStateException If the parsing fails.
     * @throws ParseException If the parsing fails.
     */
    private PresetRewards loadRewards(ConfigurationSection section, BlockPreset preset) {
        if (section == null) {
            return new PresetRewards();
        }

        PresetRewards rewards = new PresetRewards();

        rewards.parseConsoleCommands(
                getStringOrList(section, "console-commands", "console-command", "commands", "command"));
        rewards.parsePlayerCommands(getStringOrList(section, "player-commands", "player-command"));
        LoadResult.tryLoad(section, "money", NumberValue.Parser::load)
                .ifNotFull(NumberValue.fixed(0))
                .apply(rewards::setMoney);

        ConfigurationSection dropSection = section.getConfigurationSection("drop-item");

        // Items Drops
        if (dropSection != null) {
            // Single drop
            if (dropSection.contains("material") || dropSection.contains("item")) {
                try {
                    DropItem drop = loadDrop(dropSection, preset);
                    if (drop != null) {
                        rewards.getDrops().add(drop);
                    }
                } catch (IllegalArgumentException e) {
                    log.warning("Failed to load drop item for event " + section.getName() + ": " + e.getMessage());
                    return rewards;
                }
            } else {
                // Multiple drops
                for (String dropName : dropSection.getKeys(false)) {
                    DropItem drop = loadDrop(dropSection.getConfigurationSection(dropName), preset);
                    if (drop != null) {
                        rewards.getDrops().add(drop);
                    }
                }
            }

            log.fine(() -> "Loaded drops " + rewards.getDrops().stream()
                    .map(DropItem::toString)
                    .collect(Collectors.joining()));
        }
        return rewards;
    }

    /**
     * @throws IllegalStateException If the parsing fails.
     */
    private DropItem loadDrop(ConfigurationSection section, BlockPreset preset) {
        if (section == null) {
            return null;
        }

        // External item id provided.
        if (section.contains("item")) {
            String item = section.getString("item");
            if (item == null) {
                return null;
            }

            String[] parts = new String[]{item};

            int index = item.indexOf(':');
            if (index != -1) {
                parts = new String[]{item.substring(0, index), item.substring(index + 1)};
            }

            String prefix = parts[0];
            String id = parts[1];

            ItemProvider provider = plugin.getItemManager().getProvider(prefix.toLowerCase());
            if (provider == null) {
                throw new IllegalStateException("Invalid prefix '" + prefix + "'");
            }

            if (!provider.exists(parts[1])) {
                throw new IllegalStateException("External item '" + id + "' doesn't exist with the providing plugin.");
            }

            DropItem drop = new ExternalDropItem(provider, id);
            drop.setDropNaturally(section.getBoolean("drop-naturally", preset.isDropNaturally()));
            LoadResult.tryLoad(section, "chance", NumberValue.Parser::load)
                    .ifNotFull(NumberValue.fixed(100))
                    .apply(drop::setChance);
            LoadResult.tryLoad(section, "amount", NumberValue.Parser::load)
                    .ifNotFull(NumberValue.fixed(1))
                    .apply(drop::setAmount);
            return drop;
        }

        XMaterial material = ParseUtil.parseMaterial(section.getString("material"));
        if (material == null) {
            throw new IllegalStateException("Material is invalid.");
        }

        MinecraftDropItem drop = new MinecraftDropItem(material);

        LoadResult.tryLoad(section, "amount", NumberValue.Parser::load)
                .ifNotFull(NumberValue.fixed(1))
                .apply(drop::setAmount);
        drop.setDisplayName(section.getString("name"));
        drop.setLore(section.getStringList("lores"));

        drop.setEnchants(Enchant.load(section.getStringList("enchants")));
        drop.setItemFlags(section.getStringList("flags").stream()
                .map(str -> ParseUtil.parseEnum(str, ItemFlag.class,
                        e -> log.warning("Could not parse ItemFlag from " + str)))
                .collect(Collectors.toSet()));

        drop.setDropNaturally(section.getBoolean("drop-naturally", preset.isDropNaturally()));

        drop.setExperienceDrop(ExperienceDrop.load(section.getConfigurationSection("exp"), drop));
        LoadResult.tryLoad(section, "chance", NumberValue.Parser::load)
                .ifNotFull(NumberValue.fixed(100))
                .apply(drop::setChance);

        drop.setCustomModelData(ParseUtil.parseInteger(section.getString("custom-model-data")));

        if (section.isSet("item-model")) {
            String key = section.getString("item-model");
            drop.setItemModel(NamespacedKey.fromString(Objects.requireNonNull(key)));
        }

        return drop;
    }

    @NotNull
    private static List<String> getStringOrList(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.get(key) == null) {
                continue;
            }

            if (section.isList(key)) {
                return section.getStringList(key);
            } else if (section.isString(key)) {
                String str = section.getString(key);
                return Collections.singletonList(str);
            }
        }
        return new ArrayList<>();
    }
}