package tc.oc.pgm.events;

import static tc.oc.pgm.util.Assert.assertNotNull;

import org.jetbrains.annotations.Nullable;
import tc.oc.pgm.api.party.Party;
import tc.oc.pgm.api.player.MatchPlayer;

/**
 * Subclass of {@link PlayerPartyChangeEvent} called in cases where the player is actually joining a
 * party i.e. {@link #getNewParty()} returns non-null.
 */
public class PlayerJoinPartyEvent extends PlayerPartyChangeEvent {
  public PlayerJoinPartyEvent(MatchPlayer player, @Nullable Party oldParty, Party newParty) {
    super(player, oldParty, assertNotNull(newParty));
  }

  /** Overridden to remove @Nullable */
  @Override
  public Party getNewParty() {
    return super.getNewParty();
  }
}
