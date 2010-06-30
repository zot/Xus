/**
 * 
 */
package xus.client;

import com.google.gwt.core.client.JavaScriptObject;

/**
 * Use local storage, for now.  Later, switch to
 * <a href="http://dvcs.w3.org/hg/IndexedDB/raw-file/tip/Overview.html">Indexed Database API</a>, used by Firefox/IE / 
 * <a href="http://dvcs.w3.org/hg/IndexedDB/raw-file/tip/Overview.html">WebSimpleDB API later</a>, used by Chrome
 * 
 * @author bill Jun 26, 2010
 *
 */
public final class LocalStorage extends JavaScriptObject {
    static native LocalStorage getStorage() /*-{
    	return $wnd.localStorage;
    }-*/;

    protected LocalStorage() {}
    public native int length() /*-{
    	return this.length;
    }-*/;
    public native String key(int index) /*-{
    	return this.key(index);
    }-*/;
    public native <T extends JavaScriptObject> T getItem(String key) /*-{
    	var item = this.getItem(key);

    	return item && JSON.parse(this.getItem(key));
    }-*/;
    public native void setItem(String key, JavaScriptObject data) /*-{
    	this.setItem(key, JSON.stringify(data));
    }-*/;
    public native void removeItem(String key) /*-{
    	this.removeItem(key);
    }-*/;
    public native void clear() /*-{
    	this.clear();
    }-*/;
}
