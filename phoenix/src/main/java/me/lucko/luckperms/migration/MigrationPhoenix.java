/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@luck.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
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

import com.mongodb.client.*;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.types.InheritanceNode;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MigrationPhoenix extends MigrationJavaPlugin {

    private LuckPerms luckPerms;
    private MongoCollection<Document> rankCollection;
    private MongoCollection<Document> userCollection;
    private MongoCollection<Document> grantCollection;

    @Override
    public void runMigration(CommandSender sender, String[] args) {
        luckPerms = getLuckPerms();
        log(sender, "Starting.");

        if (args.length < 1) {
            sender.sendMessage("Error: You must provide the MongoDB connection URI.");
            return;
        }

        String connectionString = args[0];
        sender.sendMessage("Starting migration from Phoenix...");

        try (MongoClient mongoClient = MongoClients.create(connectionString)) {
            System.out.println("Listing all databases:");
            for (String dbName : mongoClient.listDatabaseNames()) {
                System.out.println("Database found: " + dbName);
            }

            MongoDatabase database = mongoClient.getDatabase("Phoenix");

            if (database == null) {
                System.out.println("Error: Database 'Phoenix' not found.");
                return;
            }

            System.out.println("Connected to database: " + database.getName());

            System.out.println("Listing all collections in the database:");
            for (String name : database.listCollectionNames()) {
                System.out.println("Collection found: " + name);
            }

            rankCollection = database.getCollection("px-ranks");
            userCollection = database.getCollection("px-profiles");
            grantCollection = database.getCollection("px-grants");

            if (rankCollection == null || userCollection == null || grantCollection == null) {
                System.out.println("Error: One or more collections not found.");
                return;
            }

            long rankCount = rankCollection.countDocuments();
            long userCount = userCollection.countDocuments();
            long grantCount = grantCollection.countDocuments();

            System.out.println("Found " + rankCount + " ranks in px-ranks.");
            System.out.println("Found " + userCount + " users in px-profiles.");
            System.out.println("Found " + grantCount + " grants in px-grants.");

            migrateRanks(rankCollection);
            migrateUsers(userCollection, grantCollection);

            sender.sendMessage("Migration completed successfully!");
        } catch (Exception e) {
            log(sender, "Error during migration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void migrateRanks(MongoCollection<Document> rankCollection) {
        long rankCount = rankCollection.countDocuments();
        System.out.println("Found " + rankCount + " ranks in the collection.");

        for (Document rankDoc : rankCollection.find()) {
            String rankName = rankDoc.getString("name");

            if (rankName == null || rankName.isEmpty()) {
                System.out.println("Skipping document with missing 'name' field.");
                continue;
            }

            List<Document> permissions = rankDoc.getList("permissions", Document.class);

            if (permissions == null || permissions.isEmpty()) {
                System.out.println("Rank '" + rankName + "' has no permissions.");
                continue;
            }

            Group lpGroup = luckPerms.getGroupManager().createAndLoadGroup(rankName).join();

            // Set group weight (priority)
            int weight = rankDoc.getInteger("priority", 0);  // Default to 0 if no priority is set
            lpGroup.data().add(Node.builder("weight." + weight).build());
            System.out.println("Set weight: " + weight + " for group: " + rankName);

            // Set prefix, suffix, and display name
            String prefix = rankDoc.getString("prefix");
            String suffix = rankDoc.getString("suffix");
            String displayName = rankDoc.getString("displayName");

            if (prefix != null && !prefix.isEmpty()) {
                lpGroup.data().add(Node.builder("prefix." + weight + "." + prefix).build());
                System.out.println("Set prefix: " + prefix + " for group: " + rankName);
            }

            if (suffix != null && !suffix.isEmpty()) {
                lpGroup.data().add(Node.builder("suffix." + weight + "." + suffix).build());
                System.out.println("Set suffix: " + suffix + " for group: " + rankName);
            }

            if (displayName != null && !displayName.isEmpty()) {
                lpGroup.data().add(Node.builder("displayname." + displayName).build());
                System.out.println("Set display name: " + displayName + " for group: " + rankName);
            }

            // Process permissions, including negative permissions
            for (Document permissionDoc : permissions) {
                String permission = permissionDoc.getString("permission");
                boolean isNegative = permission.startsWith("-");
                permission = isNegative ? permission.substring(1) : permission;

                lpGroup.data().add(Node.builder(permission).value(!isNegative).build());
                System.out.println("Added permission: " + permission + " to group: " + rankName + " with value: " + !isNegative);
            }

            // Process inherited groups (parents)
            List<String> parents = rankDoc.getList("inheritance", String.class);
            if (parents != null && !parents.isEmpty()) {
                for (String parentId : parents) {
                    Document parentRank = rankCollection.find(new Document("_id", parentId)).first();
                    if (parentRank != null) {
                        String parentName = parentRank.getString("name");
                        lpGroup.data().add(InheritanceNode.builder(parentName).build());
                        System.out.println("Added parent group: " + parentName + " to group: " + rankName);
                    }
                }
            }

            luckPerms.getGroupManager().saveGroup(lpGroup);
            System.out.println("Migrated group: " + rankName);
        }
    }

    private void migrateUsers(MongoCollection<Document> userCollection, MongoCollection<Document> grantCollection) {
        long userCount = userCollection.countDocuments();
        System.out.println("Found " + userCount + " users in the collection.");

        for (Document userDoc : userCollection.find()) {
            UUID playerUUID = UUID.fromString(userDoc.getString("_id"));

            if (playerUUID == null) {
                System.out.println("Skipping document with missing '_id' field.");
                continue;
            }

            List<Document> permissions = userDoc.getList("permissions", Document.class);

            if (permissions == null || permissions.isEmpty()) {
                System.out.println("User '" + playerUUID + "' has no permissions.");
                continue;
            }

            User lpUser = luckPerms.getUserManager().loadUser(playerUUID).join();

            // Process user permissions, including negative permissions
            for (Document permissionDoc : permissions) {
                String permission = permissionDoc.getString("permission");
                boolean isNegative = permission.startsWith("-");
                permission = isNegative ? permission.substring(1) : permission;

                lpUser.data().add(Node.builder(permission).value(!isNegative).build());
                System.out.println("Added permission: " + permission + " to user: " + playerUUID + " with value: " + !isNegative);
            }

            // Migrate active grants to the user
            migrateActiveGrants(grantCollection, playerUUID, lpUser);

            luckPerms.getUserManager().saveUser(lpUser);
            luckPerms.getUserManager().cleanupUser(lpUser);

            System.out.println("Migrated user: " + userDoc.getString("name"));
        }
    }

    private void migrateActiveGrants(MongoCollection<Document> grantCollection, UUID playerUUID, User lpUser) {
        long grantCount = grantCollection.countDocuments(new Document("target", playerUUID.toString()));
        System.out.println("[Grant] Found " + grantCount + " grants for user: " + playerUUID);

        for (Document grantDoc : grantCollection.find(new Document("target", playerUUID.toString()))) {

            if (!grantDoc.containsKey("_id")) {
                System.out.println("[Warning] Skipping grant without _id field for target:" + playerUUID);
                continue;
            }

            boolean active = grantDoc.getBoolean("active");

            // Skip the grant if it has been removed (removedBy is not null)
            if (grantDoc.get("removedBy") != null) {
                System.out.println("[Info] Skipping removed grant for target: " + playerUUID);
                continue;
            }

            // Handle issuedAt and duration as either String or Long
            long issuedAt = 0;
            long duration = 0;

            try {
                Object issuedAtObj = grantDoc.get("issuedAt");
                Object durationObj = grantDoc.get("duration");

                if (issuedAtObj instanceof String) {
                    issuedAt = Long.parseLong((String) issuedAtObj);
                } else if (issuedAtObj instanceof Long) {
                    issuedAt = (Long) issuedAtObj;
                }

                if (durationObj instanceof String) {
                    duration = Long.parseLong((String) durationObj);
                } else if (durationObj instanceof Long) {
                    duration = (Long) durationObj;
                }

            } catch (NumberFormatException e) {
                System.out.println("[Error] Failed to parse issuedAt or duration for target: " + playerUUID);
                continue;
            }

            long expirationTime = issuedAt + duration;

            // Check if the grant is permanent or has expired
            boolean permanentGrant = grantDoc.containsKey("permanent") && grantDoc.getBoolean("permanent");

            if (!active || (!permanentGrant && Instant.now().toEpochMilli() > expirationTime)) {
                System.out.println("[Info] Skipping expired or removed grant for target:" + playerUUID);
                continue;
            }

            String rankId = grantDoc.getString("rankId");  // Corrected to use "rankId" instead of "_id"

            // Fetch the corresponding rank from the rank collection, not the grant collection
            Document correspondingRank = rankCollection.find(new Document("_id", rankId)).first();

            if (correspondingRank != null) {
                String rankName = correspondingRank.getString("name");

                if (rankName == null || rankName.isEmpty()) {
                    System.out.println("[Warning] Rank name is null or empty for rankId: " + rankId);
                    continue;  // Skip this iteration if the rank name is invalid
                }

                // Log the expiration time or mark as permanent
                if (permanentGrant) {
                    System.out.println("[Info] Grant is permanent for target:" + playerUUID);
                    lpUser.data().add(InheritanceNode.builder(rankName).build());
                    System.out.println("[Grant] Assigned permanent grant to user: " + lpUser.getUsername() +
                            ", Rank: " + rankName);

                } else {
                    System.out.println("[Grant] Assigned temporary grant to user: " +
                            lpUser.getUsername() +
                            ", Rank: " +
                            rankName +
                            ", Expires at: "
                            +
                            Instant.ofEpochMilli(expirationTime));

                    lpUser.data().add(InheritanceNode.builder(rankName).expiry(expirationTime / 1000L).build());

                    System.out.println("[Grant] Temporary Grant assigned to user: " + lpUser.getUsername() + " expires at: " + Instant.ofEpochMilli(expirationTime));
                }

            } else {
                System.out.println("[Warning] Corresponding rank not found for rankId: " + rankId);
            }
        }
    }
}