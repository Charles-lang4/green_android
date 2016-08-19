package com.greenaddress.greenbits.spv;

import android.content.Intent;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.util.Log;

import com.blockstream.libwally.Wally;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.greenaddress.greenapi.Network;
import com.greenaddress.greenapi.PreparedTransaction;
import com.greenaddress.greenbits.GaService;
import com.subgraph.orchid.TorClient;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.net.BlockingClientManager;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SPV {

    private final static String TAG = SPV.class.getSimpleName();

    private final Map<TransactionOutPoint, Coin> mCountedUtxoValues = new HashMap<>();

    private final String VERIFIED = "verified_utxo_";
    private final String SPENDABLE = "verified_utxo_spendable_value_";

    private final Map<Integer, Coin> mVerifiedCoinBalances = new HashMap<>();
    private final Map<Sha256Hash, List<Integer>> mUnspentOutputsOutpoints = new HashMap<>();
    private final Map<TransactionOutPoint, Integer> mUnspentOutpointsSubaccounts = new HashMap<>();
    private final Map<TransactionOutPoint, Integer> mUnspentOutpointsPointers = new HashMap<>();
    private final GaService mService;
    private BlockChainListener mBlockChainListener;
    private int mBlocksRemaining = Integer.MAX_VALUE;
    private BlockStore mBlockStore;
    private BlockChain mBlockChain;
    private PeerGroup mPeerGroup;
    private PeerFilterProvider mPeerFilter;

    public SPV(final GaService service) {
        mService = service;
    }

    public GaService getService() {
        return mService;
    }

    public void onTrustedPeersChanged() {
        new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(Object[] params) {
                final boolean alreadySyncing = stopSPVSync();
                setUpSPV();
                if (alreadySyncing)
                    startSPVSync();
                return null;
            }
        }.execute();
    }

    public void setEnabled(final boolean enabled) {

        new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(final Object[] params) {
                if (enabled != mService.isSPVEnabled()) {
                    mService.cfgEdit("SPV").putBoolean("enabled", enabled).apply();
                    if (enabled) {
                        setUpSPV();
                        startSPVSync();
                    } else
                        stopSPVSync();
                }
                return null;
            }
        }.execute();
    }

    public void setSyncOnMobileEnabled(final boolean enabled) {

        new AsyncTask<Object, Object, Object>() {
            @Override
            protected Object doInBackground(final Object[] params) {
                if (enabled != mService.isSPVSyncOnMobileEnabled()) {
                    mService.cfgEdit("SPV").putBoolean("mobileSyncEnabled", enabled).apply();
                    onNetConnectivityChanged(mService.getNetworkInfo());
                }
                return null;
            }
        }.execute();
    }

    public void startIfEnabled() {
        resetUnspent();

        if (mService.isSPVEnabled()) {
            setUpSPV();
            startSPVSync();
        }
        updateUnspentOutputs();
    }

    public Coin getVerifiedBalance(final int subAccount) {
        return mVerifiedCoinBalances.get(subAccount);
    }

    public Map<Sha256Hash, List<Integer>> getUnspentOutputsOutpoints() {
        return mUnspentOutputsOutpoints;
    }

    public void updateUnspentOutputs() {
        if (!mService.isSPVEnabled())
            return;

        Futures.addCallback(mService.getAllUnspentOutputs(0, null), new FutureCallback<ArrayList>() {
            @Override
            public void onSuccess(final ArrayList result) {
                final Set<TransactionOutPoint> newUtxos = new HashSet<>();
                boolean recalculateBloom = false;

                for (int i = 0; i < result.size(); ++i) {
                    final Map<?, ?> utxo = (Map) result.get(i);
                    final String txhash = (String) utxo.get("txhash");
                    final Integer blockHeight = (Integer) utxo.get("block_height");
                    final Integer prevIndex = ((Integer) utxo.get("pt_idx"));
                    final Integer subaccount = ((Integer) utxo.get("subaccount"));
                    final Integer pointer = ((Integer) utxo.get("pointer"));
                    final Sha256Hash sha256Hash = Sha256Hash.wrap(txhash);

                    if (!mService.cfgIn(VERIFIED).getBoolean(txhash, false)) {
                        recalculateBloom = true;
                        addToBloomFilter(blockHeight, sha256Hash, prevIndex, subaccount, pointer);
                    } else {
                        // already verified
                        addToUtxo(sha256Hash, prevIndex, subaccount, pointer);
                        addUtxoToValues(sha256Hash);
                    }
                    newUtxos.add(new TransactionOutPoint(Network.NETWORK, prevIndex, sha256Hash));
                }

                final List<Integer> changedSubaccounts = new ArrayList<>();
                for (final TransactionOutPoint oldUtxo : new HashSet<>(mCountedUtxoValues.keySet())) {
                    if (!newUtxos.contains(oldUtxo)) {
                        recalculateBloom = true;
                        final int subAccount = mUnspentOutpointsSubaccounts.get(oldUtxo);
                        final Coin verifiedBalance = getVerifiedBalance(subAccount);
                        mVerifiedCoinBalances.put(subAccount,
                                                  verifiedBalance.subtract(mCountedUtxoValues.get(oldUtxo)));
                        changedSubaccounts.add(subAccount);
                        mCountedUtxoValues.remove(oldUtxo);
                        mUnspentOutpointsSubaccounts.remove(oldUtxo);
                        mUnspentOutpointsPointers.remove(oldUtxo);
                        mUnspentOutputsOutpoints.get(oldUtxo.getHash()).remove(((int) oldUtxo.getIndex()));
                    }
                }

                if (recalculateBloom && mPeerGroup != null)
                    mPeerGroup.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.SEND_IF_CHANGED);

                for (final int subAccount : changedSubaccounts)
                    mService.fireBalanceChanged(subAccount);
            }

            @Override
            public void onFailure(final Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void resetUnspent() {
        mUnspentOutpointsSubaccounts.clear();
        mUnspentOutpointsPointers.clear();
        mUnspentOutputsOutpoints.clear();
        mCountedUtxoValues.clear();
        mVerifiedCoinBalances.clear();
    }

    private void updateBalance(final TransactionOutPoint txOutpoint, final int subAccount, final Coin addValue) {
        if (mCountedUtxoValues.containsKey(txOutpoint))
           return;
        mCountedUtxoValues.put(txOutpoint, addValue);
        final Coin verifiedBalance = getVerifiedBalance(subAccount);
        if (verifiedBalance == null)
            mVerifiedCoinBalances.put(subAccount, addValue);
        else
            mVerifiedCoinBalances.put(subAccount, verifiedBalance.add(addValue));
    }

    public void addUtxoToValues(final Sha256Hash txHash) {
        final String txHashStr = txHash.toString();
        final List<Integer> changedSubaccounts = new ArrayList<>();
        boolean missing = false;
        for (final Integer outpoint : mUnspentOutputsOutpoints.get(txHash)) {
            final String key = txHashStr + ":" + outpoint;
            final long value = mService.cfgIn(SPENDABLE).getLong(key, -1);
            if (value != -1) {
                final TransactionOutPoint txOutpoint = new TransactionOutPoint(Network.NETWORK, outpoint, txHash);
                final int subAccount = mUnspentOutpointsSubaccounts.get(txOutpoint);
                if (!mCountedUtxoValues.containsKey(txOutpoint))
                    changedSubaccounts.add(subAccount);
                updateBalance(txOutpoint, subAccount, Coin.valueOf(value));
            } else {
                missing = true;
            }
        }
        for (final int subAccount : changedSubaccounts)
            mService.fireBalanceChanged(subAccount);

        if (!missing) return;
        Futures.addCallback(mService.getRawUnspentOutput(txHash), new FutureCallback<Transaction>() {
            @Override
            public void onSuccess(final Transaction result) {
                final List<Integer> changedSubaccounts = new ArrayList<>();
                final List<ListenableFuture<Boolean>> futuresList = new ArrayList<>();
                if (result.getHash().equals(txHash)) {
                    for (final Integer outpoint : mUnspentOutputsOutpoints.get(txHash)) {
                        final TransactionOutPoint txOutpoint = new TransactionOutPoint(Network.NETWORK, outpoint, txHash);
                        if (mCountedUtxoValues.containsKey(txOutpoint))
                            continue;
                        final int subAccount = mUnspentOutpointsSubaccounts.get(txOutpoint);
                        final Integer pointer = mUnspentOutpointsPointers.get(txOutpoint);

                        futuresList.add(Futures.transform(mService.verifySpendableBy(result.getOutput(outpoint), subAccount, pointer), new Function<Boolean, Boolean>() {
                            @Override
                            public Boolean apply(final Boolean input) {
                                if (input) {
                                    final Coin value = result.getOutput(outpoint).getValue();
                                    updateBalance(txOutpoint, subAccount, value);
                                    changedSubaccounts.add(subAccount);
                                    final String key = txHashStr + ":" + outpoint;
                                    mService.cfgInEdit(SPENDABLE).putLong(key, value.longValue()).apply();
                                } else {
                                    Log.e(TAG,
                                            new Formatter().format("txHash %s outpoint %s not spendable!",
                                                    txHash.toString(), outpoint.toString()).toString());
                                }
                                return input;
                            }
                        }));
                    }
                } else {
                    Log.e(TAG,
                            new Formatter().format("txHash mismatch: expected %s != %s received",
                                    txHash.toString(), result.getHash().toString()).toString());
                }
                Futures.addCallback(Futures.allAsList(futuresList), new FutureCallback<List<Boolean>>() {
                    @Override
                    public void onSuccess(final List<Boolean> result) {
                        for (final int subAccount : changedSubaccounts)
                            mService.fireBalanceChanged(subAccount);
                    }

                    @Override
                    public void onFailure(final Throwable t) {
                        t.printStackTrace();
                    }
                });
            }

            @Override
            public void onFailure(final Throwable t) {
                t.printStackTrace();
            }
        });
    }


    public void addToBloomFilter(final Integer blockHeight, final Sha256Hash txhash, final int prevIndex, final int subAccount, final int pointer) {
        if (mBlockChain == null)
            return; // can happen before login (onNewBlock)
        if (txhash != null) {
            addToUtxo(txhash, prevIndex, subAccount, pointer);
        }
        if (blockHeight != null && blockHeight <= mBlockChain.getBestChainHeight() &&
                (txhash == null || !mUnspentOutputsOutpoints.containsKey(txhash))) {
            // new tx or block notification with blockHeight <= current blockHeight means we might've [1]
            // synced the height already while we haven't seen the tx, so we need to re-sync to be able
            // to verify it.
            // [1] - "might've" in case of txhash == null (block height notification),
            //       because it depends on the order of notifications
            //     - "must've" in case of txhash != null, because this means the tx arrived only after
            //       requesting it manually and we already had higher blockHeight
            //
            // We do it using the special case in bitcoinj for VM crashed because of
            // a transaction received.
            try {
                final Wallet fakeWallet = new Wallet(Network.NETWORK) {
                    @Override
                    public int getLastBlockSeenHeight() {
                        return blockHeight - 1;
                    }
                };
                mBlockChain.addWallet(fakeWallet);
                mBlockChain.removeWallet(fakeWallet);  // can be removed, because the call above
                // should rollback already
            } catch (final Exception e) {
                // FIXME: Seems this often happens, at least on initial startup
                Log.w(TAG, "fakeWallet exception: " + e.toString());
            }
        }
    }

    private void addToUtxo(final Sha256Hash txhash, final Integer prevIndex, final int subAccount, final int pointer) {
        mUnspentOutpointsSubaccounts.put(new TransactionOutPoint(Network.NETWORK, prevIndex, txhash), subAccount);
        mUnspentOutpointsPointers.put(new TransactionOutPoint(Network.NETWORK, prevIndex, txhash), pointer);
        if (mUnspentOutputsOutpoints.get(txhash) == null) {
            mUnspentOutputsOutpoints.put(txhash, Lists.newArrayList(prevIndex));
        } else {
            mUnspentOutputsOutpoints.get(txhash).add(prevIndex);
        }
    }

    private ListenableFuture<Boolean>
    verifyOutputSpendable(final PreparedTransaction ptx, final int index) {
        return mService.verifySpendableBy(ptx.decoded.getOutputs().get(index),
                                          ptx.subAccount, ptx.change_pointer);
    }

    public ListenableFuture<Coin>
    validateTx(final PreparedTransaction ptx, final String recipientStr, final Coin amount) {
        Address recipient = null;
        try {
            recipient = Address.fromBase58(Network.NETWORK, recipientStr);
        } catch (final AddressFormatException e) {
        }

        // 1. Find the change output:
        ListenableFuture<List<Boolean>> changeFn = Futures.immediateFuture(null);

        if (ptx.decoded.getOutputs().size() == 2) {
            changeFn = Futures.allAsList(Lists.newArrayList(verifyOutputSpendable(ptx, 0),
                                                            verifyOutputSpendable(ptx, 1)));
        }
        else if (ptx.decoded.getOutputs().size() > 2)
            throw new IllegalArgumentException("Verification: Wrong number of transaction outputs.");

        // 2. Verify the main output value and address, if available:
        final Address recipientAddr = recipient;
        return Futures.transform(changeFn, new Function<List<Boolean>, Coin>() {
            @Override
            public Coin apply(final List<Boolean> input) {
                return Verifier.verify(mCountedUtxoValues, ptx, recipientAddr, amount, input);
            }
        });
    }

    // FIXME: This is copied in network monitor
    class Node {
        final String addr;
        final int port;

        Node(final String address) {
            final int index_port = address.indexOf(":");
            if (index_port != -1) {
                addr = address.substring(0, index_port);
                port = Integer.parseInt(address.substring(index_port + 1));
            } else {
                addr = address;
                port = Network.NETWORK.getPort();
            }
        }
        public String toString(){
            return String.format("%s:%d", addr, port);
        }
    }

    public int getSPVBlocksRemaining() {
        if (mService.isSPVEnabled())
            return mBlocksRemaining;
        return 0;
    }

    public PeerGroup getPeerGroup(){
        return mPeerGroup;
    }

    public int getSPVHeight() {
        if (mBlockChain != null && mService.isSPVEnabled())
            return mBlockChain.getBestChainHeight();
        return 0;
    }

    private synchronized void startSPVSync() {
        if (mPeerGroup.isRunning())
             return;

        Futures.addCallback(mPeerGroup.startAsync(), new FutureCallback<Object>() {
            @Override
            public void onSuccess(final Object result) {
                mPeerGroup.startBlockChainDownload(new DownloadProgressTracker() {
                    @Override
                    public void onChainDownloadStarted(final Peer peer, final int blocksLeft) {
                        mBlocksRemaining = blocksLeft;
                    }

                    @Override
                    public void onBlocksDownloaded(final Peer peer, final Block block, final FilteredBlock filteredBlock, final int blocksLeft) {
                        mBlocksRemaining = blocksLeft;
                    }
                });
            }

            @Override
            public void onFailure(final Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private boolean isOnion(final String addr) { return addr.toLowerCase().contains(".onion"); }

    private void addPeer(String address) {

        if (!address.contains(".")) {
            // Blank or a host name - Use the built in list, resolving via DNS
            mPeerGroup.addPeerDiscovery(new DnsDiscovery(Network.NETWORK));
            return;
        }

        final Node n = new Node(address);
        final PeerAddress peer;
        try {
            if (!isOnion(address))
                peer = new PeerAddress(InetAddress.getByName(n.addr), n.port);
            else {
                peer = new PeerAddress(InetAddress.getLocalHost(), n.port) {
                               public InetSocketAddress toSocketAddress() {
                                   return InetSocketAddress.createUnresolved(n.addr, n.port);
                               }
                           };
            }
        } catch (final UnknownHostException e) {
            // FIXME: Should report this error
            e.printStackTrace();
            return;
        }

        mPeerGroup.addAddress(peer);
    }

    private synchronized void setUpSPV(){
        //teardownSPV must be called if SPV already exists
        //and stopSPV if previous still running.
        if (mPeerGroup != null) {
            Log.d(TAG, "Must stop and tear down SPV before setting up again!");
            return;
        }

        try {
            mBlockStore = new SPVBlockStore(Network.NETWORK, mService.getSPVChainFile());
            final StoredBlock storedBlock = mBlockStore.getChainHead(); // detect corruptions as early as possible
            if (storedBlock.getHeight() == 0 && !Network.NETWORK.equals(NetworkParameters.fromID(NetworkParameters.ID_REGTEST))) {
                InputStream is = null;
                try {
                    is = mService.getAssets().open("checkpoints");
                    CheckpointManager.checkpoint(Network.NETWORK, is, mBlockStore, mService.getLoginData().earliest_key_creation_time);
                } catch (final IOException e) {
                    // couldn't load checkpoints, log & skip
                    e.printStackTrace();
                } finally {
                    try {
                        if (is != null) {
                            is.close();
                        }
                    } catch (final IOException e) {
                        // do nothing
                    }
                }
            }
            mBlockChain = new BlockChain(Network.NETWORK, mBlockStore);
            if (mBlockChainListener != null)
                mBlockChainListener.onDispose();
            mBlockChainListener = new BlockChainListener(this);
            mBlockChain.addListener(mBlockChainListener);

            System.setProperty("user.home", mService.getFilesDir().toString());
            final String peers = mService.getTrustedPeers();

            String proxyHost = mService.getProxyHost();
            String proxyPort = mService.getProxyPort();

            if (proxyHost == null || proxyPort == null)
                proxyHost = proxyPort = "";

            System.setProperty("socksProxyHost", proxyHost);
            System.setProperty("socksProxyPort", proxyPort);

            if (!proxyHost.isEmpty() && !proxyPort.isEmpty()) {
                final org.bitcoinj.core.Context context = new org.bitcoinj.core.Context(Network.NETWORK);
                mPeerGroup = new PeerGroup(context, mBlockChain, new BlockingClientManager());
            } else if (isOnion(peers)) {
                try {
                    final org.bitcoinj.core.Context context = new org.bitcoinj.core.Context(Network.NETWORK);
                    mPeerGroup = PeerGroup.newWithTor(context, mBlockChain, new TorClient(), false);
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            } else {
                mPeerGroup = new PeerGroup(Network.NETWORK, mBlockChain);
            }

            if (mPeerFilter != null)
                mPeerFilter.onDispose();
            mPeerFilter = new PeerFilterProvider(this);
            mPeerGroup.addPeerFilterProvider(mPeerFilter);

            final ArrayList<String> addresses;
            addresses = new ArrayList<String>(Arrays.asList(peers.split(",")));
            if (addresses.isEmpty())
                addresses.add(Network.DEFAULT_PEER); // Usually empty, set for regtest
            for (final String address: addresses)
                addPeer(address);
            mPeerGroup.setMaxConnections(addresses.size());

        } catch (final BlockStoreException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean stopSPVSync() {

        final boolean isRunning = mPeerGroup != null && mPeerGroup.isRunning();

        if (isRunning) {
            final Intent i = new Intent("PEERGROUP_UPDATED");
            i.putExtra("peergroup", "stopSPVSync");
            mService.sendBroadcast(i);

            mPeerGroup.stop();
        }

        if (mBlockChain != null && mBlockChainListener != null) {
            mBlockChain.removeListener(mBlockChainListener);
            mBlockChainListener.onDispose();
            mBlockChainListener = null;
        }

        if (mPeerGroup != null) {
            if (mPeerFilter != null) {
                mPeerGroup.removePeerFilterProvider(mPeerFilter);
                mPeerFilter.onDispose();
                mPeerFilter = null;
            }
            mPeerGroup = null;
        }

        if (mBlockStore != null) {
            try {
                mBlockStore.close();
                mBlockStore = null;
            } catch (final BlockStoreException x) {
                throw new RuntimeException(x);
            }
        }

        return isRunning;
    }

    public void onNetConnectivityChanged(final NetworkInfo info) {
        // FIXME
    }

    public void reset() {
        final boolean enabled = mService.isSPVEnabled();
        if (enabled)
            stopSPVSync();

        // Delete all data
        mService.getSPVChainFile().delete();

        try {
            mService.cfgInEdit(SPENDABLE).clear().commit();
            mService.cfgInEdit(VERIFIED).clear().commit();
        } catch (final NullPointerException e) {
            // ignore
        }

        resetUnspent();

        if (enabled) {
            // Restart SPV
            setUpSPV();
            // FIXME: enabled under WiFi only
            startSPVSync();
        }
    }
}
