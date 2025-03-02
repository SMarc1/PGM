package tc.oc.pgm.listeners;

import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static tc.oc.pgm.util.Assert.assertNotNull;

import java.util.Collection;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleUpdateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.event.BlockTransformEvent;
import tc.oc.pgm.api.map.GameRule;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchManager;
import tc.oc.pgm.api.match.event.MatchFinishEvent;
import tc.oc.pgm.api.match.event.MatchLoadEvent;
import tc.oc.pgm.api.match.event.MatchStartEvent;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.player.VanishManager;
import tc.oc.pgm.api.setting.SettingKey;
import tc.oc.pgm.api.setting.SettingValue;
import tc.oc.pgm.events.MapPoolAdjustEvent;
import tc.oc.pgm.events.PlayerJoinMatchEvent;
import tc.oc.pgm.events.PlayerParticipationStopEvent;
import tc.oc.pgm.gamerules.GameRulesMatchModule;
import tc.oc.pgm.modules.WorldTimeModule;
import tc.oc.pgm.util.UsernameFormatUtils;
import tc.oc.pgm.util.named.NameStyle;
import tc.oc.pgm.util.nms.NMSHacks;
import tc.oc.pgm.util.text.TemporalComponent;
import tc.oc.pgm.util.text.TextTranslations;

public class PGMListener implements Listener {
  /*
  1000  /time set day
  6000  noon, sun is at its peak
  12610 dusk
  13000 /time set night
  14000
  18000 midnight, moon is at its peak
  */
  private static final long[] WORLD_TIMES = {1000, 6000, 12610, 13000, 14000, 18000};

  private final Plugin parent;
  private final MatchManager mm;
  private final VanishManager vm;

  // Single-write, multi-read lock used to create the first match
  private final ReentrantReadWriteLock lock;

  public PGMListener(Plugin parent, MatchManager mm, VanishManager vm) {
    this.parent = parent;
    this.mm = mm;
    this.vm = vm;
    this.lock = new ReentrantReadWriteLock();
  }

  @EventHandler(ignoreCancelled = true)
  public void onPrePlayerLogin(final AsyncPlayerPreLoginEvent event) {
    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
        || mm.getMatches().hasNext()) return;

    // Create the match when the first player joins
    if (lock.writeLock().tryLock()) {
      // If the server is suspended, need to release so match can be created
      NMSHacks.resumeServer();

      try {
        mm.createMatch(null).get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      } finally {
        lock.writeLock().unlock();
      }
    }

    // If a match is being created, wait until its done
    try {
      lock.readLock().tryLock(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      lock.readLock().unlock();
    }

    if (!mm.getMatches().hasNext()) {
      event.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          TextTranslations.translate("misc.incorrectWorld"));
    }
  }

  @EventHandler
  public void onPlayerLogin(final PlayerLoginEvent event) {
    // allow premiums to join when the server is full
    if (event.getResult() == Result.KICK_FULL) {
      if (event.getPlayer().hasPermission(Permissions.JOIN_FULL)) {
        event.allow();
      } else {
        event.setKickMessage(TextTranslations.translate("misc.serverFull", event.getPlayer()));
      }
    }
  }

  @EventHandler(priority = EventPriority.LOW)
  public void addPlayerOnJoin(final PlayerJoinEvent event) {
    // Player already left. Because quit already happened, we must ignore the join.
    if (!event.getPlayer().isOnline()) return;

    Match match = this.mm.getMatch(event.getPlayer().getWorld());
    if (match == null) {
      event
          .getPlayer()
          .kickPlayer(
              ChatColor.RED + TextTranslations.translate("misc.incorrectWorld", event.getPlayer()));
      this.parent
          .getLogger()
          .info(
              "Had to kick player "
                  + event.getPlayer().getName()
                  + " due to them spawning in the incorrect world");
      return;
    }

    match.addPlayer(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void broadcastJoinMessage(final PlayerJoinEvent event) {
    // Handle join message and send it to all players except the one joining
    Match match = this.mm.getMatch(event.getPlayer().getWorld());
    if (match == null) return;

    if (event.getJoinMessage() != null) {
      event.setJoinMessage(null);
      MatchPlayer player = match.getPlayer(event.getPlayer());
      if (player != null) {
        // Announce actual staff join
        announceJoinOrLeave(player, true, vm.isVanished(player.getId()));
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void removePlayerOnDisconnect(PlayerQuitEvent event) {
    MatchPlayer player = this.mm.getPlayer(event.getPlayer());
    if (player == null) return;

    if (event.getQuitMessage() != null) {
      // Announce actual staff quit
      announceJoinOrLeave(player, false, vm.isVanished(player.getId()));
      event.setQuitMessage(null);
    }

    player.getMatch().removePlayer(event.getPlayer());
  }

  public static void announceJoinOrLeave(MatchPlayer player, boolean join, boolean staffOnly) {
    announceJoinOrLeave(player, join, staffOnly, false);
  }

  public static void announceJoinOrLeave(
      MatchPlayer player, boolean join, boolean staffOnly, boolean force) {
    assertNotNull(player);
    Collection<MatchPlayer> viewers =
        player.getMatch().getPlayers().stream()
            .filter(p -> !staffOnly || p.getBukkit().hasPermission(Permissions.STAFF))
            .collect(Collectors.toList());

    for (MatchPlayer viewer : viewers) {
      if (player.equals(viewer)) continue;
      if (!staffOnly && player.isVanished() && viewer.getBukkit().hasPermission(Permissions.STAFF))
        continue; // Skip staff during fake broadcast

      final String key =
          (join ? "misc.join" : "misc.leave")
              + (staffOnly && (player.isVanished() || force) ? ".quiet" : "");

      SettingValue option = viewer.getSettings().getValue(SettingKey.JOIN);
      if (option.equals(SettingValue.JOIN_ON)) {
        Component component =
            translatable(key, NamedTextColor.YELLOW, player.getName(NameStyle.FANCY));
        viewer.sendMessage(
            staffOnly
                ? ChatDispatcher.ADMIN_CHAT_PREFIX.append(component.color(NamedTextColor.YELLOW))
                : component.color(NamedTextColor.YELLOW));
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void protect36(final PlayerInteractEvent event) {
    if (event.getClickedBlock() != null) {
      if (event.getClickedBlock().getType() == Material.PISTON_MOVING_PIECE) {
        event.setCancelled(true);
      }
    }
  }

  // sometimes arrows stuck in players persist through deaths
  @EventHandler
  public void fixStuckArrows(final PlayerRespawnEvent event) {
    NMSHacks.clearArrowsInPlayer(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void clearActiveEnderPearls(final PlayerDeathEvent event) {
    for (Entity entity : event.getEntity().getWorld().getEntitiesByClass(EnderPearl.class)) {
      if (((EnderPearl) entity).getShooter() == event.getEntity()) {
        entity.remove();
      }
    }
  }

  // fix item pickup to work the way it should
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void handleItemPickup(final PlayerPickupItemEvent event) {
    Player nearestPlayer = event.getPlayer();
    double closestDistance =
        event.getItem().getLocation().distance(event.getPlayer().getLocation());

    for (Entity nearEntity : event.getItem().getNearbyEntities(1.5, 1.5, 1.5)) {
      double distance = event.getItem().getLocation().distanceSquared(nearEntity.getLocation());

      if (nearEntity instanceof Player && distance < closestDistance) {
        nearestPlayer = (Player) nearEntity;
        closestDistance = distance;
      }
    }

    if (nearestPlayer != event.getPlayer()) event.setCancelled(true);
  }

  @EventHandler
  public void lockFireTick(final MatchLoadEvent event) {
    setGameRule(event, GameRule.DO_FIRE_TICK.getId(), false);
  }

  @EventHandler
  public void unlockFireTick(final MatchStartEvent event) {
    event
        .getMatch()
        .getWorld()
        .setGameRuleValue(
            GameRule.DO_FIRE_TICK.getId(),
            event
                .getMatch()
                .needModule(GameRulesMatchModule.class)
                .getGameRule(GameRule.DO_FIRE_TICK.getId()));
  }

  @EventHandler
  public void lockFireTick(final MatchFinishEvent event) {
    setGameRule(event, GameRule.DO_FIRE_TICK.getId(), false);
  }

  //
  // Time Lock
  // lock time before, during (if time lock enabled), and after the match
  //
  @EventHandler
  public void lockTime(final MatchLoadEvent event) {
    setGameRule(event, GameRule.DO_DAYLIGHT_CYCLE.getId(), false);
  }

  @EventHandler
  public void unlockTime(final MatchStartEvent event) {
    event
        .getMatch()
        .getWorld()
        .setGameRuleValue(
            GameRule.DO_DAYLIGHT_CYCLE.getId(),
            event
                .getMatch()
                .needModule(GameRulesMatchModule.class)
                .getGameRule(GameRule.DO_DAYLIGHT_CYCLE.getId()));
  }

  @EventHandler
  public void lockTime(final MatchFinishEvent event) {
    setGameRule(event, GameRule.DO_DAYLIGHT_CYCLE.getId(), false);
  }

  @EventHandler
  public void setTime(final MatchLoadEvent event) {
    Long time = event.getMatch().getModule(WorldTimeModule.class).getTime();
    if (time != null) {
      event.getMatch().getWorld().setTime(time);
    }
  }

  @EventHandler
  public void randomTime(final MatchLoadEvent event) {
    if (event.getMatch().getModule(WorldTimeModule.class).isTimeRandom()) {
      Random rand = event.getMatch().getRandom();
      long time = WORLD_TIMES[rand.nextInt(WORLD_TIMES.length)];
      event.getMatch().getWorld().setTime(time);
    }
  }

  @EventHandler
  public void freezeWorld(final BlockTransformEvent event) {
    Match match = this.mm.getMatch(event.getWorld());
    if (match == null || match.isFinished()) event.setCancelled(true);
  }

  @EventHandler
  public void freezeVehicle(final VehicleUpdateEvent event) {
    Match match = this.mm.getMatch(event.getVehicle().getWorld());
    if (match == null || match.isFinished()) {
      event.getVehicle().setVelocity(new Vector());
    }
  }

  @EventHandler
  public void nerfFishing(PlayerFishEvent event) {
    if (event.getCaught() instanceof Item) {
      Item caught = (Item) event.getCaught();
      if (caught.getItemStack().getType() != Material.RAW_FISH) {
        caught.setItemStack(new ItemStack(Material.RAW_FISH));
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void dropItemsOnQuit(PlayerParticipationStopEvent event) {
    MatchPlayer quitter = event.getPlayer();
    if (!quitter.isAlive()) return;

    for (ItemStack item : quitter.getInventory().getContents()) {
      if (item == null || item.getType() == Material.AIR) continue;
      quitter.getBukkit().getWorld().dropItemNaturally(quitter.getBukkit().getLocation(), item);
    }

    for (ItemStack armor : quitter.getInventory().getArmorContents()) {
      if (armor == null || armor.getType() == Material.AIR) continue;
      quitter.getBukkit().getWorld().dropItemNaturally(quitter.getBukkit().getLocation(), armor);
    }
  }

  @EventHandler
  public void announceDynamicMapPoolChange(MapPoolAdjustEvent event) {
    // Send feedback to staff, alerting them that the map pool has changed by force
    if (event.isForced()) {
      Component poolName = text(event.getNewPool().getName(), NamedTextColor.LIGHT_PURPLE);
      Component staffName =
          UsernameFormatUtils.formatStaffName(event.getSender(), event.getMatch());
      Component matchLimit =
          text()
              .append(text(event.getMatchLimit(), NamedTextColor.GREEN))
              .append(space())
              .append(
                  translatable(
                      "match.name" + (event.getMatchLimit() != 1 ? ".plural" : ""),
                      NamedTextColor.GRAY))
              .build();

      // No limit
      Component forced = translatable("pool.change.force", poolName, staffName);
      if (event.getTimeLimit() != null) {
        Component time =
            TemporalComponent.briefNaturalApproximate(event.getTimeLimit())
                .color(NamedTextColor.GREEN);

        // If time & match limit are present, display both
        if (event.getMatchLimit() != 0) {
          Component timeAndLimit = translatable("misc.or", NamedTextColor.GRAY, time, matchLimit);
          forced = translatable("pool.change.forceTimed", poolName, timeAndLimit, staffName);
        } else {
          // Just time limit
          forced = translatable("pool.change.forceTimed", poolName, time, staffName);
        }
      } else if (event.getMatchLimit() != 0) {
        // Just match limit
        forced = translatable("pool.change.forceTimed", poolName, matchLimit, staffName);
      }

      ChatDispatcher.broadcastAdminChatMessage(forced.color(NamedTextColor.GRAY), event.getMatch());
    }

    // Broadcast map pool changes due to size
    if (event.getNewPool().isDynamic()) {
      Component broadcast =
          text()
              .append(text("[", NamedTextColor.WHITE))
              .append(translatable("pool.name", NamedTextColor.GOLD))
              .append(text("] ", NamedTextColor.WHITE))
              .append(
                  translatable(
                      "pool.change",
                      NamedTextColor.GREEN,
                      text(event.getNewPool().getName(), NamedTextColor.AQUA)))
              .build();

      event.getMatch().sendMessage(broadcast);
    }
  }

  @EventHandler // We only need to store skins for the post match stats
  public void storeSkinOnMatchJoin(PlayerJoinMatchEvent event) {
    final MatchPlayer player = event.getPlayer();
    PGM.get().getDatastore().setSkin(player.getId(), NMSHacks.getPlayerSkin(player.getBukkit()));
  }

  public void setGameRule(MatchLoadEvent event, String gameRule, boolean gameRuleValue) {
    event.getMatch().getWorld().setGameRuleValue(gameRule, Boolean.toString(gameRuleValue));
  }

  public void setGameRule(MatchFinishEvent event, String gameRule, boolean gameRuleValue) {
    event.getMatch().getWorld().setGameRuleValue(gameRule, Boolean.toString(gameRuleValue));
  }
}
