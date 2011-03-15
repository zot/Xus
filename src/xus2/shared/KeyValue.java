package xus2.shared;

public class KeyValue<K,V> {
	K key;
	V value;
	public KeyValue(K key, V value) {
		this.key = key;
		this.value = value;
	}
	
	public K getKey() {
		return key;
	}
	
	public V getValue() {
		return value;
	}
}
