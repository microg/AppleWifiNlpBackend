package org.microg.nlp.backend.apple;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import com.google.android.maps.*;
import org.microg.nlp.api.LocationHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PregrabActivity extends Activity {
	private static final String TAG = PregrabActivity.class.getName();

	private final List<WifiOverlayItem> items = new ArrayList<WifiOverlayItem>();
	private MapView mapView;
	private WifiLocationDatabase database;
	private Drawable overlayDrawable;
	private Overlay wifisOverlay;
	private CenterOverlay centerOverlay;
	private LocationRetriever retriever;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.pregrab);
		database = new WifiLocationDatabase(this);
		retriever = new LocationRetriever();
		mapView = (MapView) findViewById(R.id.map);
		overlayDrawable = getResources().getDrawable(R.drawable.dot);
		wifisOverlay = new Overlay();
		centerOverlay = new CenterOverlay();
		mapView.getOverlays().add(wifisOverlay);
		mapView.getOverlays().add(new MyLocationOverlay(this, mapView));
		mapView.getOverlays().add(centerOverlay);
		mapView.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				if (event.getAction() == MotionEvent.ACTION_UP) {
					refresh();
				}
				return false;
			}
		});
		findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				findViewById(R.id.button).setEnabled(false);
				new Thread(new Runnable() {
					@Override
					public void run() {
						String now = database.getNear(LocationHelper.create("temp", mapView.getMapCenter().getLatitudeE6() / 1E6F, mapView.getMapCenter().getLongitudeE6() / 1E6F, 0), 1).get(0).getExtras().getString("MAC_ADDRESS");
						try {
							Collection<Location> response = retriever.retrieveLocations(now);
							WifiLocationDatabase.Editor editor = database.edit();
							for (Location location : response) {
								editor.put(location);
							}
							editor.end();
						} catch (IOException e) {
							Log.w(TAG, e);
						}
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								refresh();
								findViewById(R.id.button).setEnabled(true);
							}
						});
					}
				}).start();
			}
		});
		refresh();
	}

	private void refresh() {
		items.clear();
		List<Location> nearCenter = database.getNear(LocationHelper.create("temp", mapView.getMapCenter().getLatitudeE6() / 1E6F, mapView.getMapCenter().getLongitudeE6() / 1E6F, 0), 500);
		for (Location location : nearCenter) {
			items.add(new WifiOverlayItem(location));
		}
		wifisOverlay.update();
		centerOverlay.update();
	}


	private class WifiOverlayItem extends OverlayItem {
		private WifiOverlayItem(Location location) {
			super(new GeoPoint((int) (location.getLatitude() * 1E6), (int) (location.getLongitude() * 1E6)), location.getExtras().getString("mac"), null);
		}
	}

	private class CenterOverlay extends ItemizedOverlay<OverlayItem> {

		public CenterOverlay() {
			super(getResources().getDrawable(android.R.drawable.presence_online));
		}

		public void update() {
			populate();
			mapView.getController().animateTo(new GeoPoint(mapView.getMapCenter().getLatitudeE6(), mapView.getMapCenter().getLongitudeE6() + 1));
		}

		@Override
		protected OverlayItem createItem(int i) {
			return new OverlayItem(mapView.getMapCenter(), null, null);
		}

		@Override
		public int size() {
			return 1;
		}
	}

	private class Overlay extends ItemizedOverlay<WifiOverlayItem> {

		public Overlay() {
			super(overlayDrawable);
		}

		public void update() {
			populate();
			mapView.getController().animateTo(new GeoPoint(mapView.getMapCenter().getLatitudeE6(), mapView.getMapCenter().getLongitudeE6() + 1));
		}

		@Override
		protected WifiOverlayItem createItem(int i) {
			return items.get(i);
		}

		@Override
		public int size() {
			return items.size();
		}
	}
}