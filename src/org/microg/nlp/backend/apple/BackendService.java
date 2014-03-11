package org.microg.nlp.backend.apple;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import org.microg.nlp.api.LocationBackendService;
import org.microg.nlp.api.LocationHelper;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class BackendService extends LocationBackendService {

	private static final String TAG = BackendService.class.getName();
	private static final long THIRTY_DAYS = 2592000000L;
	private final BroadcastReceiver wifiBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			reportUpdate();
		}
	};
	private final LocationRetriever retriever = new LocationRetriever();
	private VerifyingWifiLocationCalculator calculator;
	private WifiLocationDatabase database;
	private WifiManager wifiManager;
	private Thread thread;
	private Set<String> toRetrieve;
	private final Runnable retrieveAction = new Runnable() {
		@Override
		public void run() {
			while (toRetrieve != null && !toRetrieve.isEmpty()) {
				Set<String> now = new HashSet<String>();
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
						toRetrieve.remove(location.getExtras().getString(LocationRetriever.EXTRA_MAC_ADDRESS));
					}
					for (String mac : now) {
						if (toRetrieve.contains(mac)) {
							Bundle extras = new Bundle();
							extras.putString(LocationRetriever.EXTRA_MAC_ADDRESS, mac);
							editor.put(LocationHelper.create("unknown", System.currentTimeMillis(), extras));
							toRetrieve.remove(mac);
						}
					}
					editor.end();
					// Forcing update, because new mapping data is available
					reportUpdate();
				} catch (Exception e) {
					Log.w(TAG, e);
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
		if (wifiManager != null) {
			if (wifiManager.isWifiEnabled() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && wifiManager.isScanAlwaysAvailable()) {
				wifiManager.startScan();
			}
		}
		return null;
	}

	private Location calculate() {
		Collection<ScanResult> scanResults = wifiManager.getScanResults();
		Set<Location> locations = new HashSet<Location>();
		Set<String> unknown = new HashSet<String>();
		for (ScanResult result : scanResults) {
			if (result.SSID.endsWith("_nomap")) {
				// It is industry standard to ignore those APs, so we'll do the same
				continue;
			}
			String mac = LocationRetriever.wellFormedMac(result.BSSID);
			Location location = database.get(mac);
			if (location != null) {
				if ((location.getTime() + THIRTY_DAYS) < System.currentTimeMillis()) {
					// Location is old, let's refresh it :)
					unknown.add(mac);
				}
				location.getExtras().putInt(LocationRetriever.EXTRA_SIGNAL_LEVEL, result.level);
				if (location.hasAccuracy() && location.getAccuracy() != -1) {
					locations.add(location);
				}
			} else {
				unknown.add(mac);
			}
		}
		Log.d(TAG, "Found " + scanResults.size() + " wifis, of whom " + locations.size() + " with location and " + unknown.size() + " unknown.");
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

	private void reportUpdate() {
		report(calculate());
	}

	@Override
	protected void onOpen() {
		database = new WifiLocationDatabase(this);
		calculator = new VerifyingWifiLocationCalculator("apple", database);
		wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		registerReceiver(wifiBroadcastReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
	}

	@Override
	protected void onClose() {
		unregisterReceiver(wifiBroadcastReceiver);
		calculator = null;
		database.close();
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}
		database = null;
		wifiManager = null;
	}
}
