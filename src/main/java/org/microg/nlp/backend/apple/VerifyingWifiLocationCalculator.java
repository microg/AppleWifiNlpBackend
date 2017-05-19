/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.nlp.backend.apple;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import org.microg.nlp.api.LocationHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VerifyingWifiLocationCalculator {
    private static final String TAG = "AppleNlpCalculator";

    private static final long ONE_DAY = 24 * 60 * 60 * 1000;
    private static final int MAX_WIFI_RADIUS = 500;
    private static final float ACCURACY_WEIGHT = 50;
    private static final int MIN_SIGNAL_LEVEL = -200;
    private final WifiLocationDatabase database;
    private final String provider;

    public VerifyingWifiLocationCalculator(String provider, WifiLocationDatabase database) {
        this.database = database;
        this.provider = provider;
    }

    private static Set<Set<Location>> divideInClasses(Set<Location> locations, double accuracy) {
        Set<Set<Location>> classes = new HashSet<Set<Location>>();
        for (Location location : locations) {
            boolean used = false;
            for (Set<Location> locClass : classes) {
                if (locationCompatibleWithClass(location, locClass, accuracy)) {
                    locClass.add(location);
                    used = true;
                }
            }
            if (!used) {
                Set<Location> locClass = new HashSet<Location>();
                locClass.add(location);
                classes.add(locClass);
            }
        }
        return classes;
    }

    private static boolean locationCompatibleWithClass(Location location, Set<Location> locClass,
                                                       double accuracy) {
        for (Location other : locClass) {
            if ((location.distanceTo(other) - location.getAccuracy() - other.getAccuracy() -
                    accuracy) < 0) {
                return true;
            }
        }
        return false;
    }

    private static void combineClasses(Set<Set<Location>> classes, double accuracy) {
        /*
         * TODO
         * The old routine was never tested and caused Exceptions in the rare case it has to do
         * something
         * Need to write a new one here, but first priority is to not cause problems
          */
    }

    public Location calculate(Set<Location> locations) {
        Set<Set<Location>> locationClasses = divideInClasses(locations, MAX_WIFI_RADIUS);
        combineClasses(locationClasses, MAX_WIFI_RADIUS);
        List<Set<Location>> clsList = new ArrayList<Set<Location>>(locationClasses);
        Collections.sort(clsList, new Comparator<Set<Location>>() {
            @Override
            public int compare(Set<Location> lhs, Set<Location> rhs) {
                return rhs.size() - lhs.size();
            }
        });
        StringBuilder sb = new StringBuilder("Build classes of size:");
        for (Set<Location> set : clsList) {
            sb.append(" ").append(set.size());
        }
        Log.d(TAG, sb.toString());
        if (!clsList.isEmpty()) {
            Set<Location> cls = clsList.get(0);
            if (cls.size() == 1) {
                Location location = cls.iterator().next();
                if (isVerified(location)) {
                    Log.d(TAG, "is single class, but verified.");
                    return location;
                }
                return null;
            } else if (cls.size() == 2) {
                boolean verified = false;
                for (Location location : cls) {
                    if (isVerified(location)) {
                        verified = true;
                        break;
                    }
                }
                if (verified) {
                    Log.d(TAG, "is dual class and verified.");
                    verify(cls);
                } else {
                    Log.d(TAG, "is dual class, but not verified.");
                }
            } else if (cls.size() > 2) {
                Log.d(TAG, "is multi class and auto-verified.");
                verify(cls);
            }
            return combine(cls);
        }
        return null;
    }

    private int getSignalLevel(Location location) {
        return Math.abs(location.getExtras().getInt(LocationRetriever.EXTRA_SIGNAL_LEVEL) -
                MIN_SIGNAL_LEVEL);
    }

    private Location combine(Set<Location> locations) {
        float minSignal = Integer.MAX_VALUE, maxSignal = Integer.MIN_VALUE;
        long verified = -1;
        for (Location location : locations) {
            minSignal = Math.min(minSignal, getSignalLevel(location));
            maxSignal = Math.max(maxSignal, getSignalLevel(location));
            if (location.getExtras().containsKey(LocationRetriever.EXTRA_VERIFIED_TIME)) {
                verified = Math.max(verified, location.getExtras().getLong(LocationRetriever.EXTRA_VERIFIED_TIME));
            }
        }

        final float finalMaxSignal = maxSignal;
        final float finalMinSignal = minSignal;
        Bundle extras = new Bundle();
        extras.putInt("COMBINED_OF", locations.size());
        if (verified != -1) {
            extras.putLong(LocationRetriever.EXTRA_VERIFIED_TIME, verified);
        }
        return LocationHelper.weightedAverage(provider, locations, new LocationHelper.LocationBalance() {
            @Override
            public double getWeight(Location location) {
                double weight = calculateWeight(location, finalMinSignal, finalMaxSignal);
                Log.d(TAG, String.format("Using with weight=%f mac=%s sig=%d acc=%f lat=%f lon=%f", weight,
                        location.getExtras().getString(LocationRetriever.EXTRA_MAC_ADDRESS),
                        location.getExtras().getInt(LocationRetriever.EXTRA_SIGNAL_LEVEL), location.getAccuracy(),
                        location.getLatitude(), location.getLongitude()));
                return weight;
            }
        }, extras);
    }

    private double calculateWeight(Location location, float minSignal, float maxSignal) {
        return Math.pow(((float) getSignalLevel(location) - minSignal) / (maxSignal - minSignal) + ACCURACY_WEIGHT / Math.max(location.getAccuracy(), ACCURACY_WEIGHT), 2);
    }

    private void verify(Set<Location> cls) {
        WifiLocationDatabase.Editor editor = database.edit();
        for (Location location : cls) {
            location.getExtras().putLong(LocationRetriever.EXTRA_VERIFIED_TIME, System.currentTimeMillis());
            editor.put(location);
        }
        editor.end();
    }

    private boolean isVerified(Location location) {
        return location.getExtras().getLong(LocationRetriever.EXTRA_VERIFIED_TIME) > System.currentTimeMillis() - ONE_DAY;
    }

}
