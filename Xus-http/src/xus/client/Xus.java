/**
 * 
 */
package xus.client;

import com.google.gwt.core.client.JsArray;


/**
 * @author bill Jun 26, 2010
 *
 */
public class Xus {
	private LocalStorage storage = LocalStorage.getStorage();
	
	private final static String ACCOUNTS = "accounts";
	
	public static native <T> T cast(Object obj) /*-{
		return obj;
	}-*/;

	public JsArray<JSMap> getAccounts() {
//		JsArray<JSMap> accounts = storage.getItem(ACCOUNTS);
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
}
