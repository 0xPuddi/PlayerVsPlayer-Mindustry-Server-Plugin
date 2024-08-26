package PlayerVsPlayer.blockchain;

import PlayerVsPlayer.blockchain.contracts.PlayerVsPlayerContract;

import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.web3j.abi.EventEncoder;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.*;
import org.web3j.tx.gas.DefaultGasProvider;
import io.reactivex.disposables.Disposable;

import arc.Events;
import arc.util.Log;

public class BlockchainClient {
    Web3j web3;
    private final String nodeURL;
    private final String contractAddress;
    private final DefaultGasProvider gp;
    private final Credentials walletCredentials;

    private String[] uuidListeners = {};
    private Map<String, Disposable> uuidListener = new HashMap<>();

    PlayerVsPlayerContract pvpContract = null;

    public BlockchainClient(String nodeURL, String contractAddress, String walletKey) {
        this.nodeURL = nodeURL;
        web3 = Web3j.build(new HttpService(this.nodeURL));

        this.contractAddress = contractAddress;
        walletCredentials = Credentials.create(walletKey);

        gp = new DefaultGasProvider();
        pvpContract = PlayerVsPlayerContract.load(contractAddress, web3, walletCredentials, gp);
    }

    // add listener
    public void addBettingListener(String uuid, String gameId) {
        EthFilter eFilter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST,
                this.contractAddress);

        Log.info("This is the topic: " + EventEncoder.encode(PlayerVsPlayerContract.PLAYERBET_EVENT));

        eFilter.addSingleTopic(EventEncoder.encode(PlayerVsPlayerContract.PLAYERBET_EVENT));

        Log.info(eFilter.getTopics());

        Disposable listener = pvpContract.playerBetEventFlowable(eFilter).subscribe(e -> {
            Log.info(e.playerId);
            Log.info(e.playerId.toString());
            Log.info(e.gameId);
            Log.info(e.gameId.toString());

            if (e.playerId.toString().equals(uuid) && e.gameId.toString().equals(gameId)) {
                // true run event
                Events.fire(new SuccessfullPlayerBet(uuid, gameId, e.amount));
            }
        }, (onError) -> {
            onError.printStackTrace();
            Events.fire(new ListenerFailed(uuid));
        });

        uuidListener.put(uuid, listener);
    }

    /*
     * ListenerFailed event
     */
    public class ListenerFailed {
        public final String uuid;

        ListenerFailed(String _uuid) {
            this.uuid = _uuid;
        }
    }

    /*
     * SuccessfullPlayerBet is triggered when a player succesfully bets in a game
     */
    public class SuccessfullPlayerBet {
        public final String uuid;
        public final String gameId;
        public final Double betAmount;

        SuccessfullPlayerBet(String _uuid, String _gameId, BigInteger _betAmount) {
            BigDecimal bigDecimalValue = new BigDecimal(_betAmount);
            BigDecimal scaledValue = bigDecimalValue.scaleByPowerOfTen(-18);
            double doubleValue = scaledValue.doubleValue();

            this.uuid = _uuid;
            this.gameId = _gameId;
            this.betAmount = doubleValue;
        }
    }

    /*
     * deleteBettingListener deletes a user listener based on uuid
     */
    public void deleteBettingListener(String uuid) {
        if (uuidListener.get(uuid) == null) {
            return;
        }

        Disposable d = uuidListener.get(uuid);
        uuidListener.put(uuid, null);

        if (d != null) {
            d.dispose();
        }
    }

    /*
     * endGameTransaction executes an EndGame transaction, waits for completion.
     */
    public boolean endGameTransaction(String[] winnersId, String gameId) {

        List<byte[]> _winnersid = new ArrayList<byte[]>();

        for (String id : winnersId) {
            _winnersid.add(id.getBytes());
        }

        byte[] _gameid = gameId.getBytes();

        RemoteFunctionCall<TransactionReceipt> tr = pvpContract.EndGame(_winnersid, _gameid, gp.getGasLimit());

        try {
            tr.send();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /*
     * gameFailedTransaction executes an GameFail transaction, waits for completion.
     */
    public boolean gameFailedTransaction(String gameId) {

        byte[] _gameid = gameId.getBytes();

        RemoteFunctionCall<TransactionReceipt> tr = pvpContract.GameFail(_gameid);

        try {
            tr.send();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /*
     * deleteAllBettingListeners deletes all listeners
     */
    public void deleteAllBettingLsteners() {
        for (String uuid : uuidListeners) {
            if (uuidListener.get(uuid) == null) {
                continue;
            }

            Disposable d = uuidListener.get(uuid);
            uuidListener.put(uuid, null);

            d.dispose();
        }
    }

    /*
     * getListeners returns all listeners uuid
     */
    public String[] getListeners(String uuid) {
        return uuidListeners;
    }
}
