package depchain.config;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Network address of a blockchain member or client.
 */
public final class NodeAddress {
    private final String host;
    private final int port;

    public NodeAddress(String host, int port) {
        this.host = Objects.requireNonNull(host);
        this.port = port;
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port: " + port);
    }

    public String getHost() { return host; }
    public int getPort() { return port; }

    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeAddress that = (NodeAddress) o;
        return port == that.port && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
