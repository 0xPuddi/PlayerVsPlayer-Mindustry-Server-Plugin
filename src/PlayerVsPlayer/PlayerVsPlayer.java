package PlayerVsPlayer;

import java.io.File;

import PlayerVsPlayer.blockchain.BlockchainClient;
import PlayerVsPlayer.lobby.Lobby;
import PlayerVsPlayer.lobby.Lobby.Actions;
import PlayerVsPlayer.net.NetClientLobby;
import PlayerVsPlayer.net.NetServerLobby;
import PlayerVsPlayer.blockchain.BlockchainClient.ListenerFailed;
import PlayerVsPlayer.blockchain.BlockchainClient.SuccessfullPlayerBet;

import arc.*;
import arc.files.Fi;
import arc.util.*;
import arc.struct.Seq;

import mindustry.server.ServerControl;
import mindustry.net.Administration.Config;
import mindustry.net.Packets.KickReason;
import mindustry.game.Gamemode;
import mindustry.game.EventType.*;
import mindustry.game.Teams.TeamData;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.mod.Mods.LoadedMod;
import mindustry.net.NetConnection;
import mindustry.net.Packets;
import mindustry.Vars;

import org.web3j.crypto.Hash;

import io.github.cdimascio.dotenv.Dotenv;

public class PlayerVsPlayer extends Plugin {
    private Dotenv dotenv;

    BlockchainClient bcClient;
    Lobby lobby;
    NetServerLobby nLobby;
    NetClientLobby nClientLobby;
    ServerControl sc = null;

    int netServerPort = Integer.parseInt(dotenv.get("NET_SERVER_PORT"));
    String netServerHost = dotenv.get("NET_SERVER_HOST");

    String gameId = null;
    Boolean gameStart = null;
    Integer playersReady = 0;

    public class GameReadyToStart {
    }

    public class GameFailed {
        public final String gameid;

        GameFailed(String _gameid) {
            this.gameid = _gameid;
        }
    }

    // called when game initializes
    @Override
    public void loadContent() {
        Log.info("Loading Content");
    }

    // called when game initializes
    @Override
    public void init() {
        dotenv = Dotenv.load();
        Log.info("Init Content");

        // Load mod requirement hjson
        try {
            LoadedMod lm = Vars.mods
                    .importMod(new Fi(new File(dotenv.get("MOD_PATH"))));
            Log.info("Requirements mod is loaded: " + lm);
        } catch (Exception i) {
            Log.info("Load Mod exception: " + i);
        }

        // Initialize classes
        bcClient = new BlockchainClient(
                dotenv.get("RPC_URL"),
                dotenv.get("CONTRACT_ADDRESS"), dotenv.get("PRIVATE_KEY"));
        lobby = new Lobby();
        nClientLobby = new NetClientLobby(lobby);
        sc = ServerControl.instance;
        Config.serverName.set("PlayerVsPlayer: In Lobby");
        Config.autoPause.set(true);

        // DeclareForfeit deletes a player from the lobby if he has not a confirmed bet
        nClientLobby.addMethodClient("DeclareForfeit", (msg) -> {
            Log.info("Received a forfeit message");
            lobby.deletePlayerIfNoBet(msg);
        });

        Events.on(GameFailed.class, (e) -> {
            Log.err("Game has failed, closing server for security reasons");
            exitServer(new String[0]);
        });

        // ListenerFailed is fired when an active listener has any error, notify and
        // abort the affected player
        Events.on(ListenerFailed.class, (e) -> {
            nClientLobby.sendPacket("ListenerFailed", " ");
            lobby.deletePlayerIfNoBet(e.uuid);
            nClientLobby.closeSocket(e.uuid);
        });

        // Player has betted succesfully, update its status
        Events.on(SuccessfullPlayerBet.class, (e) -> {
            lobby.updatePlayerBetStatus(e.uuid, e.betAmount);
            bcClient.deleteBettingListener(e.uuid);
        });

        // Game fired when is ready to start, notify all betting players
        Events.on(GameReadyToStart.class, (e) -> {
            String[] playersLobby = lobby.getAllPlayersInLobby();

            for (String uuidPlayer : playersLobby) {
                if (!lobby.hasPlayerConfirmedTheBet(uuidPlayer)) {
                    continue;
                }

                Log.info("Sending game ready socket packet lobby to: " + uuidPlayer);

                NetClientLobby playerNetClientLobby = NetClientLobby.uuidClientHandles.get(uuidPlayer);
                playerNetClientLobby.sendPacket("GameReady", " ");
            }

        });

        // A PlayerAction event, triggered when a write occurs in the lobby
        Events.on(Lobby.PlayerAction.class, (e) -> {
            Log.info("Event has fired from player id: " + e.uuid);

            if (e.action == Actions.ADDED) {
                nClientLobby.waitConnection(e.uuid);
                bcClient.addBettingListener(e.uuid, this.gameId);
                return;
            }

            if (e.action == Actions.DELETED) {
                bcClient.deleteBettingListener(e.uuid);
            }

            if (e.action == Actions.BET_UPDATE) {
                playersReady += 1;

                if (playersReady >= 4) {
                    gameStart = true;
                    Events.fire(GameReadyToStart.class);
                }
            }

            ClientPacketReliableCallPacket packet = getUpToDateLobbyDataPacket(true, "");
            String[] playersLobby = lobby.getAllPlayersInLobby();

            for (String uuidPlayer : playersLobby) {
                if (uuidPlayer.equals(e.uuid)) {
                    continue;
                }

                Log.info("Sending updated socket packet lobby to: " + e.uuid);

                NetClientLobby playerNetClientLobby = NetClientLobby.uuidClientHandles.get(uuidPlayer);
                if (playerNetClientLobby != null) {
                    playerNetClientLobby.sendPacket(packet.type, packet.contents);
                }
            }
        });

        // Standard mindustry ConnectPacketEvent event
        Events.on(ConnectPacketEvent.class, (e) -> {
            Packets.ConnectPacket pk = e.packet;
            NetConnection con = e.connection;

            String uuid = pk.uuid;
            String name = pk.name;

            // Server is not ready yet
            if (gameStart == null) {
                con.kick("Server is not yet ready");
            }

            // Player is a new entry
            if (!lobby.isPlayerInTheLobby(uuid)) {
                Integer index = name.indexOf("?");
                if (index != -1) {
                    name = name.replaceAll("\\?", "");
                }

                lobby.addPlayer(uuid, name, (double) 0);
            }

            // Player is yet to bet
            if (!lobby.hasPlayerConfirmedTheBet(uuid)) {
                ClientPacketReliableCallPacket responseLobbyPacketConnection = new ClientPacketReliableCallPacket();
                responseLobbyPacketConnection = getUpToDateLobbyDataPacket(false, uuid);
                con.send(responseLobbyPacketConnection, true);

                Time.runTask(1000f, () -> {
                    try {
                        con.kick("Undefined Client Handling");
                    } catch (Error err) {
                    }
                });
            }

            // game on
            if (gameStart != null && !gameStart) {
                ClientPacketReliableCallPacket responseLobbyPacketConnection = new ClientPacketReliableCallPacket();
                responseLobbyPacketConnection = getUpToDateLobbyDataPacket(false, uuid);
                con.send(responseLobbyPacketConnection, true);

                Time.runTask(1000f, () -> {
                    try {
                        con.kick("Wait for the Game to start, players needed: " + 10);
                    } catch (Error err) {
                    }
                });
            }

            // Game started
            if (gameStart != null && !gameStart) {
                Config.serverName.set("PlayerVsPlayer: In Game");
            }
        });

        Events.on(GameOverEvent.class, e -> {
            // close lobby
            this.gameStart = null;
            this.playersReady = 0;

            int winnerId = e.winner.id;
            Seq<TeamData> td = Vars.state.teams.getActive();

            TeamData winningTeamData = null;
            for (TeamData t : td) {
                if (winnerId == t.team.id) {
                    winningTeamData = t;
                }
            }

            if (winningTeamData == null) {
                Events.fire(new GameFailed(this.gameId));
            }

            Player[] winningPlayers = winningTeamData.players.items;
            String[] uuidWinners = new String[winningPlayers.length];

            for (int i = 0; i < winningPlayers.length; ++i) {
                uuidWinners[i] = Hash.sha3String(winningPlayers[i].con.uuid);
            }

            // eliminate all lobby state
            String[] playersInLobby = lobby.getAllPlayersInLobby();
            for (String p : playersInLobby) {
                lobby.deletePlayer(p);
            }
            lobby.closeGame();

            // kick all players
            Vars.netServer.kickAll(KickReason.valueOf("Game Ended, winners: " + e.winner.id));

            // distribute rewards
            boolean txExecuted = bcClient.endGameTransaction(uuidWinners, this.gameId);

            // Error end game tx
            if (!txExecuted) {
                Log.err("Game failed, id: " + this.gameId);
                for (String uuid : uuidWinners) {
                    Log.err("Winners uuid: " + uuid);
                }
                Events.fire(new GameFailed(this.gameId));
            }

            // Start new lobby
            setGame();
        });

        // Initialize socket lobby server
        nLobby = new NetServerLobby(netServerPort);
        nLobby.startServer();
    }

    // setGame sets the pvp gameId
    public void setGame() {
        Log.info("Starting a new game");

        // create gameId
        this.gameId = Hash.sha3String(java.util.UUID.randomUUID().toString());
        Log.info("Game Id: " + gameId);

        this.gameStart = false;
    }

    // register commands that run on the server
    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("host", "Host is depreciated and replaced by hostpvp with this server plugin", args -> {
            //
        });

        // Override the exit command to close the lobby socket
        handler.register("exit", "Exit the server application. Overridden method to adapt it to PlayerVsPlayer",
                args -> {
                    exitServer(args);
                });

        // Override the exit command to close the lobby socket
        handler.register("hostpvp", "Host a PvP betting match",
                args -> {
                    if (!Vars.state.isGame()) {
                        setGame();
                        String mapName = Vars.maps.getShuffleMode().next(Gamemode.pvp, Vars.state.map).plainName();
                        sc.handleCommandString("host " + mapName + " pvp"); // pvp
                    }
                });
    }

    /*
     * exitServer shuts down server socket and game server
     */
    private void exitServer(String[] args) {
        Log.info("Shutting down server.");
        bcClient.deleteAllBettingLsteners();
        nLobby.closeServerSocket();
        Vars.net.dispose();
        Core.app.exit();
    }

    /*
     * getUpToDateLobbyDataPacket creates a LobbyData packet with up to data lobby
     * informations
     */
    private ClientPacketReliableCallPacket getUpToDateLobbyDataPacket(boolean isSocketPacket, String uuid) {
        ClientPacketReliableCallPacket responseLobbyPacket = new ClientPacketReliableCallPacket();
        responseLobbyPacket.type = "LobbyData";

        if (isSocketPacket) {
            responseLobbyPacket.type = "SocketLobbyData";
        }

        responseLobbyPacket.contents = "";

        String lobbyData = "";
        String[] players = lobby.getAllPlayersInLobby();

        // Get names
        lobbyData += "names:";
        for (String p : players) {
            String n = lobby.getPlayerName(p);

            if (n == "names") {
                n = "playerNames";
            }

            if (n == "betamount") {
                n = "playerBetamount";
            }

            if (n == "confirmed") {
                n = "playerConfirmed";
            }

            lobbyData += n + "?";
        }

        // Get Betting amounts
        lobbyData += "betamount:";
        for (String p : players) {
            String ba = Double.toString(lobby.getPlayerBetAmount(p));
            lobbyData += ba + "?";
        }

        // Get confirmation
        lobbyData += "confirmed:";
        for (String p : players) {
            String bc = Boolean.toString(lobby.hasPlayerConfirmedTheBet(p));
            lobbyData += bc + "?";
        }

        String packetFormat = "gameid:" + this.gameId;
        lobbyData += packetFormat;

        // add ip, port and uuid for lobby socket
        if (!isSocketPacket) {
            lobbyData += "socketinfo:" + this.netServerHost + "?" + this.netServerPort;
            lobbyData += "uuidUserServer:" + uuid;
        }

        responseLobbyPacket.contents = lobbyData;

        return responseLobbyPacket;
    }
}
