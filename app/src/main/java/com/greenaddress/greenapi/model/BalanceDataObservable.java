package com.greenaddress.greenapi.model;

import android.util.Log;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.greenaddress.greenapi.data.BalanceData;

import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import static com.greenaddress.gdk.GDKSession.getSession;

public class BalanceDataObservable extends Observable implements Observer {
    private Map<String,BalanceData> mBalanceData;
    private ListeningExecutorService mExecutor;
    private Integer mSubaccount;

    private BalanceDataObservable() {}

    public BalanceDataObservable(final ListeningExecutorService executor,
                                 final Integer subaccount) {
        mExecutor = executor;
        mSubaccount = subaccount;
    }

    public void refresh() {
        mExecutor.submit(() -> {
            try {
                final Map<String,BalanceData> balance = getSession().getBalance(mSubaccount, 0);
                setBalanceData(balance);
            } catch (Exception e) {
                Log.e("OBS", e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public Map<String,BalanceData> getBalanceData() {
        return mBalanceData;
    }

    public BalanceData getBtcBalanceData() {
        return mBalanceData.get("btc");
    }

    public Integer getSubaccount() {
        return mSubaccount;
    }

    public void setBalanceData(final Map<String,BalanceData> mBalanceData) {
        Log.d("OBS", "setBalanceData(" +  mSubaccount + ", " + mBalanceData + ")");
        this.mBalanceData = mBalanceData;
        fire();
    }

    @Override
    public void update(final Observable observable, final Object o) {
        if (observable instanceof BlockchainHeightObservable) {
            refresh();
        }
    }

    public void fire() {
        setChanged();
        notifyObservers();
    }
}
