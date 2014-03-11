package org.microg.nlp.backend.apple;

import android.location.Location;
import android.os.Bundle;
import com.squareup.wire.Wire;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class LocationRetriever {
	public static final String EXTRA_CHANNEL = "CHANNEL";
	public static final String EXTRA_MAC_ADDRESS = "MAC_ADDRESS";
	public static final String EXTRA_SIGNAL_LEVEL = "SIGNAL_LEVEL";
	public static final String EXTRA_VERIFIED_TIME = "VERIFIED_TIME";
	private static final byte[] APPLE_MAGIC_BYTES =
			{0, 1, 0, 5, 101, 110, 95, 85, 83, 0, 0, 0, 11, 52, 46, 50, 46, 49, 46, 56, 67, 49, 52, 56, 0, 0, 0, 1, 0,
					0, 0};
	private static final String SERVICE_HOST = "iphone-services.apple.com";
	private static final String SERVICE_URL = "https://" + SERVICE_HOST + "/clls/wloc";
	private static final String HTTP_FIELD_CONTENT_TYPE = "Content-Type";
	private static final String HTTP_FIELD_CONTENT_LENGTH = "Content-Length";
	private static final String CONTENT_TYPE_URLENCODED = "application/x-www-form-urlencoded";
	private static final float WIRE_LATLON = 1E8F;
	private final Wire wire = new Wire();

	private static byte[] combineBytes(byte[] first, byte[] second, byte divider) {
		byte[] bytes = new byte[first.length + second.length + 1];
		System.arraycopy(first, 0, bytes, 0, first.length);
		bytes[first.length] = divider;
		System.arraycopy(second, 0, bytes, first.length + 1, second.length);
		return bytes;
	}

	private static HttpsURLConnection createConnection() throws IOException {
		return createConnection(SERVICE_URL);
	}

	private static HttpsURLConnection createConnection(String url) throws IOException {
		return createConnection(new URL(url));
	}

	private static HttpsURLConnection createConnection(URL url) throws IOException {
		return (HttpsURLConnection) url.openConnection();
	}

	private static Request createRequest(String... macs) {
		List<Request.RequestWifi> wifis = new ArrayList<Request.RequestWifi>();
		for (final String mac : macs) {
			wifis.add(new Request.RequestWifi.Builder().mac(mac).build());
		}
		return new Request.Builder().source("com.apple.maps").unknown3(0).unknown4(0).wifis(wifis).build();
	}

	private static void prepareConnection(HttpsURLConnection connection, int length) throws ProtocolException {
		connection.setRequestMethod("POST");
		connection.setDoInput(true);
		connection.setDoOutput(true);
		connection.setUseCaches(false);
		connection.setRequestProperty(HTTP_FIELD_CONTENT_TYPE, CONTENT_TYPE_URLENCODED);
		connection.setRequestProperty(HTTP_FIELD_CONTENT_LENGTH, String.valueOf(length));
	}

	private static byte[] readStreamToEnd(InputStream is) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (is != null) {
			byte[] buff = new byte[1024];
			while (true) {
				int nb = is.read(buff);
				if (nb < 0) {
					break;
				}
				bos.write(buff, 0, nb);
			}
			is.close();
		}
		return bos.toByteArray();
	}

	/**
	 * Bring a mac address to the form FF:FF:FF:FF:FF:FF
	 *
	 * @param mac mac to be cleaned
	 * @return cleaned up mac
	 */
	public static String wellFormedMac(String mac) {
		int HEX_RADIX = 16;
		int[] bytes = new int[6];
		String[] splitAtColon = mac.split(":");
		if (splitAtColon.length == 6) {
			for (int i = 0; i < 6; ++i) {
				bytes[i] = Integer.parseInt(splitAtColon[i], HEX_RADIX);
			}
		} else {
			String[] splitAtLine = mac.split("-");
			if (splitAtLine.length == 6) {
				for (int i = 0; i < 6; ++i) {
					bytes[i] = Integer.parseInt(splitAtLine[i], HEX_RADIX);
				}
			} else if (mac.length() == 12) {
				for (int i = 0; i < 6; ++i) {
					bytes[i] = Integer.parseInt(mac.substring(i * 2, (i + 1) * 2), HEX_RADIX);
				}
			} else if (mac.length() == 17) {
				for (int i = 0; i < 6; ++i) {
					bytes[i] = Integer.parseInt(mac.substring(i * 3, (i * 3) + 2), HEX_RADIX);
				}
			} else {
				throw new IllegalArgumentException("Can't read this string as mac address");

			}
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 6; ++i) {
			String hex = Integer.toHexString(bytes[i]);
			if (hex.length() == 1) {
				hex = "0" + hex;
			}
			if (sb.length() != 0)
				sb.append(":");
			sb.append(hex);
		}
		return sb.toString();
	}

	private static Location fromResponseWifi(Response.ResponseWifi wifi) {
		if (wifi == null || wifi.mac == null) return null;
		String mac = wellFormedMac(wifi.mac);
		Location location = new Location(SERVICE_HOST);
		Bundle extras = new Bundle();
		extras.putString(EXTRA_MAC_ADDRESS, mac);
		if (wifi.location.latitude != null) {
			location.setLatitude(wifi.location.latitude / WIRE_LATLON);
		}
		if (wifi.location.longitude != null) {
			location.setLongitude(wifi.location.longitude / WIRE_LATLON);
		}
		if (wifi.location.altitude != null && wifi.location.altitude > -500) {
			location.setAltitude(wifi.location.altitude);
		}
		if (wifi.location.accuracy != null) {
			location.setAccuracy(wifi.location.accuracy);
		}
		if (wifi.channel != null) {
			extras.putInt(EXTRA_CHANNEL, wifi.channel);
		}
		location.setExtras(extras);
		location.setTime(System.currentTimeMillis());
		return location;
	}

	public Collection<Location> retrieveLocations(String... macs) throws IOException {
		Request request = createRequest(macs);
		byte[] byteb = request.toByteArray();
		byte[] bytes = combineBytes(APPLE_MAGIC_BYTES, byteb, (byte) byteb.length);
		HttpsURLConnection connection = createConnection();
		prepareConnection(connection, bytes.length);
		OutputStream out = connection.getOutputStream();
		out.write(bytes);
		out.flush();
		out.close();
		InputStream in = connection.getInputStream();
		in.skip(10);
		Response response = wire.parseFrom(readStreamToEnd(in), Response.class);
		in.close();
		Collection<Location> locations = new ArrayList<Location>();
		for (Response.ResponseWifi wifi : response.wifis) {
			locations.add(fromResponseWifi(wifi));
		}
		return locations;
	}

	public Collection<Location> retrieveLocations(Collection<String> macs) throws IOException {
		return retrieveLocations(macs.toArray(new String[macs.size()]));
	}
}
