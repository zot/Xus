/**
 * 
 */
package xus.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.ui.AbsolutePanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.VerticalSplitPanel;

/**
 * Why is "Peer" in the "client" package?  Because GWT requires it.
 * 
 * Peer is a demo class that uses the Xus infrastructure to communicate
 * 
 * @author bill Jun 25, 2010
 *
 */
public class Peer implements EntryPoint {
	private Xus xus = new Xus();
	private HTML chat;
	private HTML whiteboard;
	private AbsolutePanel mainPanel;
	private VerticalSplitPanel comPanel;
	private HTML accountPanel;
	private Element accountName;
	private Element accountPassword;

	public void onModuleLoad() {
		RootPanel root = RootPanel.get("root");
		HTML ops = HTML.wrap(DOM.getElementById("operations"));

		accountPanel = HTML.wrap(DOM.getElementById("accountForm"));
		comPanel = new VerticalSplitPanel();
		xus.clearStorage();
		mainPanel = new AbsolutePanel();
		mainPanel.setStylePrimaryName("main");
		chat = HTML.wrap(DOM.getElementById("chat"));
		whiteboard = HTML.wrap(DOM.getElementById("whiteboard"));
		root.setSize("100%", "100%");
		mainPanel.add(comPanel);
		root.add(mainPanel);
		mainPanel.add(accountPanel);
		comPanel.setTopWidget(whiteboard);
		comPanel.setBottomWidget(chat);
		comPanel.setSplitPosition("50%");
		comPanel.addStyleName("com");
		root.add(ops);
		bindOperations();
		DOM.getElementById("loading").addClassName("hidden");
		root.removeStyleName("hidden");
		accountName = DOM.getElementById("accountName");
		accountPassword = DOM.getElementById("accountPassword");
	}

	private void bindOperations() {
		new EventHandler("onclick") {
			@Override public void onEvent(Event e) {
				listAccounts();
			}
		}.install(DOM.getElementById("listAccounts"));
		new EventHandler("onclick") {
			@Override public void onEvent(Event e) {
				showAccountForm();
			}
		}.install(DOM.getElementById("createAccount"));
		new EventHandler("onclick") {
			@Override public void onEvent(Event e) {
				createAccount();
			}
		}.install(DOM.getElementById("accountOK"));
		new EventHandler("onclick") {
			@Override public void onEvent(Event e) {
				hideAccountForm();
			}
		}.install(DOM.getElementById("accountCancel"));
	}
	
	protected void createAccount() {
		JsArray<JSMap> accounts = xus.getAccounts();
		JSMap acct = JSMap.create();

		acct.put("name", accountName.getPropertyObject("value"));
		acct.put("password", accountPassword.getPropertyObject("value"));
		accounts.push(acct);
		xus.storeAccount(accounts);
		hideAccountForm();
	}

	protected void showAccountForm() {
		comPanel.getElement().addClassName("hidden");
		DOM.getElementById("accountForm").removeClassName("hidden");
	}

	protected void hideAccountForm() {
		comPanel.getElement().removeClassName("hidden");
		DOM.getElementById("accountForm").addClassName("hidden");
	}

	protected void listAccounts() {
		whiteboard.setHTML("CHECKING ACCOUNTS");
		JsArray<JSMap> accounts = xus.getAccounts();
		
		if (accounts.length() == 0) {
			whiteboard.setHTML("NO ACCOUNTS");
		} else {
			StringBuffer buf = new StringBuffer();
			
			buf.append("Accounts:");
			for (int i = 0; i < accounts.length(); i++) {
				buf.append("<br>");
				buf.append(accounts.get(i).get("name"));
				buf.append(": ");
				buf.append(accounts.get(i).get("password"));
			}
			whiteboard.setHTML(buf.toString());
		}
	}
	
	public void alert(Object... args) {
		StringBuffer buf = new StringBuffer();

		for (Object obj: args) {
			buf.append(obj);
		}
		basicAlert(buf.toString());
	}
	
	public native void basicAlert(String arg) /*-{
		alert(arg);
	}-*/;

	public void oldOnModuleLoad() {
		String msg = "bubba";
		byte msgBytes[] = Base64Coder.bytes(msg);
		JsCryptoSHA256 digest = JsCryptoSHA256.newInstance();
		StringBuffer buf = new StringBuffer();

		digest.update(msgBytes);
		append(buf, msg, ": ", digest.digest(), "<br>");
		msgBytes[msgBytes.length - 1] = 'Q';
		digest.update(msgBytes);
		append(buf, Base64Coder.string(msgBytes), ": ", digest.digest(), "<br>");
		msgBytes[msgBytes.length - 1] = 'a';
		digest.update(msgBytes);
		append(buf, Base64Coder.string(msgBytes), ": ", digest.digest(), "<br>");
		DOM.getElementById("msg").setInnerHTML(buf.toString());
	}
	
	public StringBuffer append(StringBuffer buf, Object... args) {
		for (Object arg: args) {
			if (arg instanceof byte[]) {
				byte[] array = (byte[])arg;

				for (byte b: array) {
					int h = b & 0xFF;

					if (h < 16) {
						buf.append('0');
					}
					buf.append(Integer.toHexString(h));
				}
			} else {
				buf.append(arg);
			}
		}
		return buf;
	}
}
