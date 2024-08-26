package PlayerVsPlayer.lobby;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import arc.Events;

public class Lobby {
    String[] playersUuid = {};
    Map<String, Integer> playerIndexMap = new HashMap<>();
    Map<String, String> playerNameMap = new HashMap<>();
    Map<String, Boolean> playerPresentMap = new HashMap<>();
    Map<String, Double> playerBetAmountMap = new HashMap<>();
    Map<String, Boolean> playerBetConfirmedMap = new HashMap<>();

    Map<String, Integer> playerConnection = new HashMap<>();

    String gameId = null;

    public enum Actions {
        ADDED,
        DELETED,
        BET_UPDATE,
    }

    public Lobby() {

    }

    // Starts the game
    public void startGame(String gameId) {
        this.gameId = gameId;
    }

    // Returns the game
    public String getGame() {
        return this.gameId;
    }

    // Closes the game
    public void closeGame() {
        this.gameId = null;
    }

    // Adds a player in the lobby
    public void addPlayer(String uuid, String name, Double betAmount) {
        playerIndexMap.put(uuid, playersUuid.length);
        playerNameMap.put(uuid, name);
        playerPresentMap.put(uuid, true);
        playerBetAmountMap.put(uuid, betAmount);
        playerBetConfirmedMap.put(uuid, false);

        playersUuid = pushElement(playersUuid, uuid);

        System.out.println("Player added to: " + uuid);
        Events.fire(new PlayerAction(uuid, Actions.ADDED));
    }

    // Deletes a player from the lobby only if he has not betted yet
    public void deletePlayerIfNoBet(String uuid) {
        if (this.hasPlayerConfirmedTheBet(uuid)) {
            return;
        }
    }

    // Deletes a players from the lobby
    public void deletePlayer(String uuid) {
        Integer index = playerIndexMap.get(uuid);
        playerIndexMap.put(uuid, null);
        playerNameMap.put(uuid, null);
        playerPresentMap.put(uuid, false);
        playerBetAmountMap.put(uuid, null);
        playerBetConfirmedMap.put(uuid, false);

        // Swap last
        Integer length = playersUuid.length;

        if (length > 1) {
            String lastUuid = playersUuid[length - 1];
            playersUuid[index] = lastUuid;
            playerIndexMap.put(lastUuid, index);
        }

        playersUuid = popElement(playersUuid);
        System.out.println("Player left lobby: " + uuid);
        Events.fire(new PlayerAction(uuid, Actions.DELETED));
    }

    // Updates the player bet amount and stores thta the bet has taken place
    public void updatePlayerBetStatus(String uuid, Double betAmount) {
        playerBetAmountMap.put(uuid, betAmount);
        playerBetConfirmedMap.put(uuid, true);

        System.out.println("Player bet update, amount: " + uuid);
        Events.fire(new PlayerAction(uuid, Actions.BET_UPDATE));
    }

    // Returns true if the player is in the lobby otherwise returns false
    public boolean isPlayerInTheLobby(String uuid) {
        if (playerPresentMap.get(uuid) == null) {
            playerPresentMap.put(uuid, false);
        }

        return playerPresentMap.get(uuid);
    }

    // Returns a player bet amount or 0
    public Double getPlayerBetAmount(String uuid) {
        return playerBetAmountMap.get(uuid);
    }

    // Returns a player name or null
    public String getPlayerName(String uuid) {
        return playerNameMap.get(uuid);
    }

    // Returns wheter a player has betted (true) or not (false)
    public boolean hasPlayerConfirmedTheBet(String uuid) {
        return playerBetConfirmedMap.get(uuid);
    }

    // Returns all player in the lobby
    public String[] getAllPlayersInLobby() {
        return playersUuid;
    }

    // Method to remove an element at a specific index from an array
    private static String[] popElement(String[] array) {
        if (array.length == 0) {
            return new String[0];
        }

        String[] newArray = Arrays.copyOf(array, array.length - 1);
        return newArray;
    }

    // Method to push an element into an array
    private static String[] pushElement(String[] array, String element) {
        String[] newArray = Arrays.copyOf(array, array.length + 1);
        newArray[array.length] = element;
        return newArray;
    }

    // Statefull PlayerAction event
    public static class PlayerAction {
        public final String uuid;
        public final Actions action;

        public PlayerAction(String uuid, Actions action) {
            this.uuid = uuid;
            this.action = action;
        }
    }
}
