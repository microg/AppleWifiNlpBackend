package org.microg.nlp.backend.apple;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import org.microg.nlp.api.LocationBackendService;
import org.microg.nlp.api.LocationHelper;
import org.microg.nlp.api.WiFiBackendHelper;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class BackendService extends LocationBackendService implements WiFiBackendHelper.Listener {

    private static final String TAG = BackendService.class.getName();
    private static final long THIRTY_DAYS = 2592000000L;
    private final LocationRetriever retriever = new LocationRetriever();
    private boolean running = false;
    private WiFiBackendHelper backendHelper;
    private VerifyingWifiLocationCalculator calculator;
    private WifiLocationDatabase database;
    private Thread thread;
    private Set<String> toRetrieve;
    private final Runnable retrieveAction = new Runnable() {
        @Override
        public void run() {
            while (toRetrieve != null && !toRetrieve.isEmpty()) {
                if (running) {
                    Set<String> now = new HashSet<>();
                    for (String s : toRetrieve) {
                        now.add(s);
                        if (now.size() == 10) break;
                    }
                    Log.d(TAG, "Requesting Apple for " + now.size() + " locations");
                    try {
                        Collection<Location> response = retriever.retrieveLocations(now);
                        WifiLocationDatabase.Editor editor = database.edit();
                        for (Location location : response) {
                            editor.put(location);
                            toRetrieve.remove(location.getExtras().getString(LocationRetriever
                                    .EXTRA_MAC_ADDRESS));
                        }
                        for (String mac : now) {
                            if (toRetrieve.contains(mac)) {
                                Bundle extras = new Bundle();
                                extras.putString(LocationRetriever.EXTRA_MAC_ADDRESS, mac);
                                editor.put(LocationHelper.create("unknown",
                                        System.currentTimeMillis(), extras));
                                toRetrieve.remove(mac);
                            }
                        }
                        editor.end();
                        // Forcing update, because new mapping data is available
                        report(calculate());
                    } catch (Exception e) {
                        Log.w(TAG, e);
                    }
                }
                synchronized (thread) {
                    try {
                        thread.wait(30000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            toRetrieve = null;
            thread = null;
        }
    };

    @Override
    protected Location update() {
        backendHelper.onUpdate();
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        backendHelper = new WiFiBackendHelper(this, this);
    }

    private synchronized Location calculate() {
        if (!running) {
            return null;
        }
        Set<WiFiBackendHelper.WiFi> wiFis = backendHelper.getWiFis();
        Set<Location> locations = new HashSet<>();
        Set<String> unknown = new HashSet<>();
        for (WiFiBackendHelper.WiFi wifi : wiFis) {
            Location location = database.get(wifi.getBssid());
            if (location != null) {
                if ((location.getTime() + THIRTY_DAYS) < System.currentTimeMillis()) {
                    // Location is old, let's refresh it :)
                    unknown.add(wifi.getBssid());
                }
                location.getExtras().putInt(LocationRetriever.EXTRA_SIGNAL_LEVEL, wifi.getRssi());
                if (location.hasAccuracy() && location.getAccuracy() != -1) {
                    locations.add(location);
                }
            } else {
                unknown.add(wifi.getBssid());
            }
        }
        Log.d(TAG, "Found " + wiFis.size() + " wifis, of whom " + locations.size() + " with " +
                "location and " + unknown.size() + " unknown.");
        if (!unknown.isEmpty()) {
            if (toRetrieve == null) {
                toRetrieve = unknown;
            } else {
                toRetrieve.addAll(unknown);
            }
        }
        if (thread == null) {
            thread = new Thread(retrieveAction);
            thread.start();
        }
        return calculator.calculate(locations);
    }

    @Override
    protected synchronized void onOpen() {
        database = new WifiLocationDatabase(this);
        calculator = new VerifyingWifiLocationCalculator("apple", database);
        backendHelper.onOpen();
        running = true;
    }

    @Override
    protected synchronized void onClose() {
        running = false;
        backendHelper.onClose();
        calculator = null;
        database.close();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        database = null;
    }

    @Override
    public void onWiFisChanged(Set<WiFiBackendHelper.WiFi> wiFis) {
        if (running) report(calculate());
    }
}
