/**
 * 
 */
package xus.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.DOM;

/**
 * Why is "Peer" in the "client" package?  Because GWT requires it.
 * 
 * @author bill Jun 25, 2010
 *
 */
public class Peer implements EntryPoint {
	public void onModuleLoad() {
		String msg = "bubba";
		byte msgBytes[] = bytes(msg);
		JsCryptoSHA256 digest = JsCryptoSHA256.newInstance();
		StringBuffer buf = new StringBuffer();

		digest.update(msgBytes);
		append(buf, msg, ": ", digest.digest(), "<br>");
		msgBytes[msgBytes.length - 1] = 'Q';
		digest.update(msgBytes);
		append(buf, string(msgBytes), ": ", digest.digest(), "<br>");
		msgBytes[msgBytes.length - 1] = 'a';
		digest.update(msgBytes);
		append(buf, string(msgBytes), ": ", digest.digest(), "<br>");
		DOM.getElementById("msg").setInnerHTML(buf.toString());
	}
	
	public byte[] bytes(String s) {
		char cA[] = s.toCharArray();
		byte bA[] = new byte[s.length()];

		for (int i = 0; i < cA.length; i++) {
			bA[i] = (byte)cA[i];
		}
		return bA;
	}
	
	public String string(byte bA[]) {
		char cA[] = new char[bA.length];

		for (int i = 0; i < cA.length; i++) {
			cA[i] = (char)bA[i];
		}
		return new String(cA);
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
