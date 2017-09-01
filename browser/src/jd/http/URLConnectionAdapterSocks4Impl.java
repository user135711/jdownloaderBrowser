package jd.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URL;

import jd.http.requests.PostFormDataRequest;
import jd.http.requests.PostRequest;

import org.appwork.utils.net.httpconnection.HTTPProxy;
import org.appwork.utils.net.httpconnection.Socks4HTTPConnectionImpl;
import org.appwork.utils.net.socketconnection.SocketConnection;
import org.brotli.dec.BrotliInputStream;

/**
 * The Class URLConnectionAdapterSocks4Impl.
 */
public class URLConnectionAdapterSocks4Impl extends Socks4HTTPConnectionImpl implements URLConnectionAdapter {
    /** The request. */
    private Request request;

    /**
     * constructor
     *
     * @param url
     *            the {@link URL}
     * @param proxy
     *            the {@link HTTPProxy}
     */
    public URLConnectionAdapterSocks4Impl(final URL url, final HTTPProxy proxy) {
        super(url, proxy);
    }

    /** {@inheritDoc} */
    @Override
    public InputStream getErrorStream() {
        try {
            return super.getInputStream();
        } catch (final IOException e) {
            return null;
        }
    }

    @Override
    public long[] getRange() {
        final long[] ret = super.getRange();
        if (ret == null && this.getResponseCode() == 206) {
            this.ranges = URLConnectionAdapterDirectImpl.buildFakeContentRange(this);
            return this.ranges;
        }
        return ret;
    }

    /** {@inheritDoc} */
    @Override
    public long getLongContentLength() {
        return this.getContentLength();
    }

    /** {@inheritDoc} */
    @Override
    public Request getRequest() {
        return this.request;
    }

    /** {@inheritDoc} */
    @Override
    public void setRequest(final Request request) {
        this.request = request;
    }

    @Override
    protected boolean isRequiresOutputStream() {
        return super.isRequiresOutputStream() || this.request != null && this.request.requireOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (this.convertedInputStream == null && !RequestMethod.HEAD.equals(this.getRequestMethod())) {
            super.getInputStream();
            if (!this.isContentDecoded()) {
                final String encoding = this.getHeaderField("Content-Encoding");
                if ("br".equalsIgnoreCase(encoding)) {
                    this.convertedInputStream = new BrotliInputStream(this.convertedInputStream);
                    this.contentDecoded = true;
                }
            }
        }
        return super.getInputStream();
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(300);
        final Request req = this.getRequest();
        if (req != null) {
            sb.append("Caller: " + req.getCaller());
            sb.append(URLConnectionAdapter.CRLF);
            sb.append("BrowserID:" + req.getBrowserID() + "|BrowserParentID:" + req.getBrowserParentID() + "|RequestID:" + req.getRequestID() + "|URL:" + req.getURL());
            sb.append(URLConnectionAdapter.CRLF);
        }
        sb.append(this.getRequestInfo());
        if (req != null) {
            if (req instanceof PostRequest) {
                final String log = ((PostRequest) req).log();
                if (log != null) {
                    sb.append(URLConnectionAdapter.CRLF);
                    sb.append(log);
                }
            } else if (req instanceof PostFormDataRequest) {
                final String postDataString = ((PostFormDataRequest) req).getPostDataString();
                if (postDataString != null) {
                    sb.append(URLConnectionAdapter.CRLF);
                    sb.append(postDataString);
                }
            }
            sb.append(URLConnectionAdapter.CRLF);
        }
        sb.append(this.getResponseInfo());
        return sb.toString();
    }

    protected SocketAddress endPointSocketAddress;

    @Override
    public void disconnect() {
        try {
            this.getEndPointSocketAddress();
        } finally {
            super.disconnect();
        }
    }

    @Override
    public SocketAddress getEndPointSocketAddress() {
        if (this.endPointSocketAddress == null) {
            this.endPointSocketAddress = SocketConnection.getRootEndPointSocketAddress(this.getConnectionSocket());
        }
        return this.endPointSocketAddress;
    }
}
