package xus2.client;

public interface SocketListener {
    void onOpen();
    void onMessage(String message);
}
