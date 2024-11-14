/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.migration;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class MigrationJavaPlugin extends JavaPlugin {

    private LuckPerms luckPerms;

    @Override
    public final void onEnable() {
        getLogger().info("-------------------------------------------");
        getLogger().info("      LuckPerms Migration Plugin");
        getLogger().info("      Version: " + getDescription().getVersion());
        getLogger().info("-------------------------------------------");

        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);

        if (luckPerms == null) {
            RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (provider != null) {
                luckPerms = provider.getProvider();
            }
        }

        if (luckPerms == null) {
            log(null, "Error: LuckPerms either not loaded or not found by the migration plugin. Make sure LuckPerms is installed and enabled!");
        }

        onPluginStartup();
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> runMigration(sender, args));
        return true;
    }

    protected abstract void runMigration(CommandSender sender, String[] args);

    protected void log(CommandSender sender, String msg) {
        if (sender != null && !(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("[migration] " + msg);
        } else {
            getLogger().info("[migration] " + msg);
        }
    }

    public LuckPerms getLuckPerms() {
        return luckPerms;
    }

    protected void onPluginStartup() {
        // do nothing
    }
}
