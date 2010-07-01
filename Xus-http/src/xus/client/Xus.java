/**
 * 
 */
package xus.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayInteger;


/**
 * @author bill Jun 26, 2010
 */
public class Xus {
	private LocalStorage storage = LocalStorage.getStorage();
	
	private static final String DATE = "Date:";
	private static final String X_XUS_PASSWORD = "X-XUS-Password:";
	private static final String X_XUS_SIGNATURE = "X-XUS-Signature:";
	private static final String X_XUS = "X-XUS-";
	private final static String ACCOUNTS = "accounts";
	public final static byte CRNL[] = Base64Coder.bytes("\r\n");
	
	public static native <T> T cast(Object obj) /*-{
		return obj;
	}-*/;

	public JsArray<JSMap> getAccounts() {
		JsArray<JSMap> accounts = LocalStorage.getStorage().getItem(ACCOUNTS);

		if (accounts == null) {
			accounts = JsArray.createArray().cast();
			storage.setItem(ACCOUNTS, accounts);
		}
		return accounts;
	}
	public void storeAccount(JsArray<JSMap> accounts) {
		storage.setItem(ACCOUNTS, accounts);
	}
	public void clearStorage() {
		storage.clear();
	}
	/**
	 * Return signature bytes for message
	 * 
	 * @param digest
	 * @param password
	 * @param headers
	 * @param body
	 * @return digest bytes from integers, low-to-high
	 */
	public byte[] signature(JsCryptoSHA256 digest, String password, ArrayList<String> headers, String body) {
		ArrayList<String> h = new ArrayList<String>(headers);

		h.add(X_XUS_PASSWORD + " " + password);
		Collections.sort(h);
		for (String header : h) {
			if (headerStartsWith(header, X_XUS) || headerStartsWith(header, DATE)) {
				digest.update(Base64Coder.bytes(header));
				digest.update(CRNL);
			}
		}
		digest.update(Base64Coder.bytes(body));
		return digestBytes(digest);
	}

	/**
	 * @param digest
	 * @return digest bytes from integers, low-to-high
	 */
	private byte[] digestBytes(JsCryptoSHA256 digest) {
		JsArrayInteger d = digest.digest();
		byte db[] = new byte[4];
		int pos = 0;
		for (int i = 0; i < d.length(); i++) {
			int v = d.get(i);

			db[pos++] = (byte)(v & 0xff);
			db[pos++] = (byte)((v >> 8) & 0xff);
			db[pos++] = (byte)((v >> 16) & 0xff);
			db[pos++] = (byte)((v >> 24) & 0xff);
		}
		return db;
	}

	private boolean headerStartsWith(String header, String prefix) {
		return header.length() >= prefix.length() && header.substring(0, prefix.length()).equalsIgnoreCase(prefix);
	}

	public boolean verify(JsCryptoSHA256 digest, String password, ArrayList<String> headers, String body) {
		ArrayList<String> h = new ArrayList<String>(headers);
		byte sig[] = null;

		h.add(X_XUS_PASSWORD + " " + password);
		Collections.sort(h);
		for (String header: h) {
			header = header.trim();
			if (headerStartsWith(header, X_XUS_SIGNATURE)) {
				sig = Base64Coder.decode(header.substring(0, X_XUS_SIGNATURE.length()).trim());
			} else if (headerStartsWith(header, X_XUS) || headerStartsWith(header, DATE)) {
				digest.update(Base64Coder.bytes(header));
				digest.update(CRNL);
			}
		}
		digest.update(Base64Coder.bytes(body));
		byte db[] = digestBytes(digest);
		return Arrays.equals(db, sig);
	}
}
