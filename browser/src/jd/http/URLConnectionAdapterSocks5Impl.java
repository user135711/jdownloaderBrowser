package jd.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import jd.http.requests.PostFormDataRequest;
import jd.http.requests.PostRequest;

import org.appwork.utils.net.httpconnection.HTTPProxy;
import org.appwork.utils.net.httpconnection.Socks5HTTPConnectionImpl;
import org.brotli.dec.BrotliInputStream;

/**
 * The Class URLConnectionAdapterSocks5Impl.
 */
public class URLConnectionAdapterSocks5Impl extends Socks5HTTPConnectionImpl implements URLConnectionAdapter {
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
    public URLConnectionAdapterSocks5Impl(final URL url, final HTTPProxy proxy) {
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
            sb.append("BrowserID:" + req.getBrowserID() + "|RequestID:" + req.getRequestID() + "|URL:" + req.getURL());
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
}
