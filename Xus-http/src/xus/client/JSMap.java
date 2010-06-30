/**
 * 
 */
package xus.client;

import com.google.gwt.core.client.JavaScriptObject;

/**
 * @author bill Jun 28, 2010
 *
 */
public final class JSMap extends JavaScriptObject {
	public static JSMap create() {
		return JavaScriptObject.createObject().cast();
	}

	protected JSMap() {}
	public native <T> T get(String key) /*-{
		return this[key];
	}-*/;
	public native void put(String key, Object value) /*-{
		this[key] = value;
	}-*/;
	public native void delete(String key) /*-{
		delete this.key;
	}-*/;
}
