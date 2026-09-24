package dev.bl.projectiletrajectorycorrection;

import org.bukkit.plugin.java.JavaPlugin;

public final class ProjectileTrajectoryCorrectionPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        Settings.load(this);

        PlayerMovementTracker tracker = new PlayerMovementTracker();
        tracker.start(this);

        getServer().getPluginManager().registerEvents(
            new ProjectileLaunchListener(this, tracker), this);
        WindChargePearlCollisionTask.start(this);

        PtcDebug.startApproachTracker(this);
        if (getCommand("ptcdebug") != null) {
            getCommand("ptcdebug").setExecutor(new PtcDebug());
        }
        Settings settings = new Settings(this);
        if (getCommand("ptc") != null) {
            getCommand("ptc").setExecutor(settings);
            getCommand("ptc").setTabCompleter(settings);
        }
        getLogger().info(getName() + " v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info(getName() + " disabled.");
    }
}
