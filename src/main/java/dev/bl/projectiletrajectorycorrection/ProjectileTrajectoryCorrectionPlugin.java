package dev.bl.projectiletrajectorycorrection;

import org.bukkit.plugin.java.JavaPlugin;

public final class ProjectileTrajectoryCorrectionPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        PlayerMovementTracker tracker = new PlayerMovementTracker();
        tracker.start(this);

        getServer().getPluginManager().registerEvents(
            new ProjectileLaunchListener(this, tracker), this);
        WindChargePearlCollisionTask.start(this);
        getLogger().info(getName() + " v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info(getName() + " disabled.");
    }
}
