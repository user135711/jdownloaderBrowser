//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.appwork.exceptions.ThrowUncheckedException;
import org.appwork.exceptions.WTFException;
import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.utils.Application;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.net.HTTPHeader;
import org.appwork.utils.net.URLHelper;
import org.appwork.utils.net.httpconnection.HTTPConnection;
import org.appwork.utils.net.httpconnection.HTTPConnectionImpl;
import org.appwork.utils.net.httpconnection.HTTPConnectionImpl.KEEPALIVE;
import org.appwork.utils.net.httpconnection.HTTPKeepAliveSocketException;
import org.appwork.utils.net.httpconnection.HTTPProxy;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.parser.UrlQuery;

import jd.nutils.encoding.Encoding;

public abstract class Request {

    public static String getCookieString(final Cookies cookies, URL url) {
        if (cookies != null && !cookies.isEmpty()) {
            final boolean secure = "https".equalsIgnoreCase(url.getProtocol());
            final StringBuilder buffer = new StringBuilder();
            for (final Cookie cookie : cookies.getCookies()) {
                // Pfade sollten verarbeitet werden...TODO
                if (cookie.isExpired()) {
                    continue;
                }
                if (!secure && Boolean.TRUE.equals(cookie.isSecure())) {
                    continue;
                }
                if (buffer.length() > 0) {
                    buffer.append("; ");
                }
                buffer.append(cookie.getKey());
                buffer.append("=");
                buffer.append(cookie.getValue());
            }
            return buffer.toString();
        }
        return null;
    }

    /**
     * Gibt eine Hashmap mit allen key:value pairs im query zurück
     * 
     * @deprecated Use UrlQuery.parse instead
     * @param query
     *            kann ein reines query ein (&key=value) oder eine url mit query
     * @return
     * @throws MalformedURLException
     */
    @Deprecated
    public static UrlQuery parseQuery(String query) throws MalformedURLException {
        return UrlQuery.parse(query);

    }

    public static byte[] read(final URLConnectionAdapter con, int readLimit) throws IOException {
        readLimit = Math.max(0, readLimit);
        final InputStream is = con.getInputStream();
        if (is == null) {
            // TODO: check if we have to close con here
            return null;
        }
        try {
            if (HTTPConnection.RequestMethod.HEAD.equals(con.getRequestMethod())) {
                if (is.read() != -1) {
                    throw new IOException("HeadRequest with content!?");
                }
                return null;
            } else {
                final long contentLength = con.getLongContentLength();
                final ByteArrayOutputStream bos;
                if (contentLength >= 0) {
                    final int length = contentLength > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) contentLength;
                    bos = new ByteArrayOutputStream(length);
                } else {
                    bos = new ByteArrayOutputStream(32767);
                }
                final String contentEncoding = con.getHeaderField("Content-Encoding");
                try {
                    int len = -1;
                    final byte[] buffer = new byte[32767];
                    while ((len = is.read(buffer)) != -1) {
                        if (len > 0) {
                            if (bos.size() + len > readLimit) {
                                throw new IOException("Content-length too big " + (bos.size() + len) + " >= " + readLimit);
                            }
                            bos.write(buffer, 0, len);
                        }
                    }
                    final String transferEncoding = con.getHeaderField("Content-Transfer-Encoding");
                    if ((con.isContentDecoded() == false || !"base64".equalsIgnoreCase(transferEncoding) && !"gzip".equalsIgnoreCase(contentEncoding) && !"deflate".equalsIgnoreCase(contentEncoding)) && contentLength >= 0 && bos.size() != contentLength) {
                        System.out.println("Incomplete content received! Content-Length: " + contentLength + " does not match Read-Length: " + bos.size());
                    }
                    return bos.toByteArray();
                } catch (final IOException e) {
                    if (bos.size() > 0) {
                        if (e instanceof EOFException && "gzip".equalsIgnoreCase(contentEncoding)) {
                            // System.out.println("Try workaround for " + Exceptions.getStackTrace(e));
                            return bos.toByteArray();
                        }
                        final String ioMessage = e.toString();
                        if (ioMessage != null && (ioMessage.contains("end of ZLIB") || ioMessage.contains("Premature") || ioMessage.contains("Corrupt GZIP trailer"))) {
                            // System.out.println("Try workaround for " + Exceptions.getStackTrace(e));
                            return bos.toByteArray();
                        }
                    }
                    throw e;
                }
            }
        } finally {
            try {
                is.close();
            } catch (final Exception e) {
            }
            try {
                /* disconnect connection */
                con.disconnect();
            } catch (final Exception e) {
            }
        }
    }

    /*
     * default timeouts, because 0 is infinite and BAD, if we need 0 then we have to set it manually
     */
    protected int                  connectTimeout = 30000;
    protected int                  readTimeout    = 60000;
    protected Cookies              cookies        = null;
    protected RequestHeader        headers;

    protected String               htmlCode;

    protected URLConnectionAdapter httpConnection;

    protected long                 readTime       = -1;

    protected boolean              requested      = false;
    protected int                  readLimit      = 1 * 1024 * 1024;

    protected HTTPProxy            proxy;

    protected URL                  url;

    protected String               customCharset  = null;

    protected byte[]               responseBytes  = null;

    protected boolean              contentDecoded = true;

    protected boolean              keepByteArray  = false;

    protected Boolean              sslTrustALL    = null;

    public Boolean isSSLTrustALLSet() {
        return this.sslTrustALL;
    }

    public void setSSLTrustALL(Boolean sslTrustALL) {
        this.sslTrustALL = sslTrustALL;
    }

    protected Request(final Request cloneRequest) {
        this.setURL(cloneRequest.getURL());
        this.setCustomCharset(cloneRequest.getCustomCharset());
        this.setReadTimeout(cloneRequest.getReadTimeout());
        this.setConnectTimeout(cloneRequest.getConnectTimeout());
        this.setReadLimit(cloneRequest.getReadLimit());
        this.setProxy(cloneRequest.getProxy());
        this.setContentDecoded(cloneRequest.isContentDecodedSet());
        if (cloneRequest.getHeaders() != null) {
            final RequestHeader headers = new RequestHeader(cloneRequest.getHeaders());
            /**
             * do not clone following headers
             */
            headers.remove(HTTPConstants.HEADER_REQUEST_REFERER);
            this.setHeaders(headers);
        } else {
            this.setHeaders(this.getDefaultRequestHeader());
        }
        this.setAuth(cloneRequest.getURL());
    }

    protected void setAuth(final URL url) {
        final String userInfo = url != null ? url.getUserInfo() : null;
        if (StringUtils.isNotEmpty(userInfo)) {
            this.getHeaders().put("Authorization", "Basic " + Encoding.Base64Encode(userInfo));
        }
    }

    public Request(final URL url) throws IOException {
        this.setURL(url);
        this.setHeaders(this.getDefaultRequestHeader());
        this.setAuth(url);
    }

    public Request(final String url) throws IOException {
        this(URLHelper.createURL(url));
    }

    public Request(final URLConnectionAdapter con) throws IOException {
        this.httpConnection = con;
        if (con.getRequest() != null) {
            this.setURL(con.getRequest().getURL());
        } else {
            this.setURL(con.getURL());
        }
        this.requested = true;
        this.getCookies().add(Cookies.parseSetCookies(this));
    }

    public Request cloneRequest() {
        throw new WTFException("Not Implemented");
    }

    /**
     * DO NEVER call this method directly... use browser.connect
     */
    protected Request connect() throws IOException {
        try {
            while (true) {
                try {
                    this.disconnect();
                    this.openConnection();
                    this.postRequest();
                    this.httpConnection.finalizeConnect();
                    try {
                        this.getCookies().add(Cookies.parseSetCookies(this));
                    } catch (final NullPointerException e) {
                        throw new IOException("Malformed url?", e);
                    }
                    return this;
                } catch (final HTTPKeepAliveSocketException ignore) {
                    // ignore.printStackTrace();
                }
            }
        } finally {
            this.requested = true;
        }
    }

    public boolean containsHTML(final String html) throws CharacterCodingException {
        final String htmlCode = this.getHtmlCode();
        return htmlCode == null ? false : htmlCode.contains(html);
    }

    public void disconnect() {
        try {
            if (this.httpConnection != null) {
                this.httpConnection.disconnect();
            }
        } catch (final Throwable ignore) {
        }
    }

    public String getCharsetFromMetaTags() {
        final String parseFrom;
        if (this.htmlCode == null && this.responseBytes != null && this.responseBytes.length > 0) {
            parseFrom = new String(this.responseBytes);
        } else if (this.htmlCode != null) {
            parseFrom = this.htmlCode;
        } else {
            return null;
        }
        String charSetMetaTag = new Regex(parseFrom, "http-equiv=(\"|'|)Content-Type(\\1)[^<>]+content=(\"|')?[^\"]+charset=([^\"<>]+)").getMatch(3);
        if (charSetMetaTag == null) {
            charSetMetaTag = new Regex(parseFrom, "meta charset=\"(.*?)\"").getMatch(0);
        }
        return charSetMetaTag;
    }

    public int getConnectTimeout() {
        return this.connectTimeout;
    }

    public long getContentLength() {
        return this.httpConnection == null ? -1 : this.httpConnection.getLongContentLength();
    }

    public Cookies getCookies() {
        if (this.cookies == null) {
            this.cookies = new Cookies();
        }
        return this.cookies;
    }

    public String getCookieString() {
        return Request.getCookieString(this.cookies, this.getURL());
    }

    public String getCustomCharset() {
        return this.customCharset;
    }

    protected RequestHeader getDefaultRequestHeader() {
        final RequestHeader headers = new RequestHeader();
        switch (CrossSystem.getOSFamily()) {
        case WINDOWS:
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64; rv:40.0) Gecko/20100101 Firefox/40.0");
            break;
        case MAC:
            headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.10; rv:40.0) Gecko/20100101 Firefox/40.0");
            break;
        case LINUX:
        default:
            headers.put("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:40.0) Gecko/20100101 Firefox/40.0");
            break;
        }
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "de,en-gb;q=0.7, en;q=0.3");
        if (Application.getJavaVersion() >= Application.JAVA16) {
            /* deflate only java >=1.6 */
            headers.put("Accept-Encoding", "gzip,deflate");
        } else {
            headers.put("Accept-Encoding", "gzip");
        }
        headers.put("Cache-Control", "no-cache");
        // headers.put("Pragma", "no-cache");
        return headers;
    }

    public RequestHeader getHeaders() {
        return this.headers;
    }

    public String getHtmlCode() throws CharacterCodingException {
        if (this.htmlCode == null && this.responseBytes != null) {
            final boolean keepBytes = this.isKeepByteArray();
            final String contentType;
            if (this.httpConnection != null) {
                contentType = this.httpConnection.getContentType();
            } else {
                contentType = null;
            }
            /* check for image content type */
            if (contentType != null && Pattern.compile("images?/\\w*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(contentType).matches()) {
                throw new IllegalStateException("Content-Type: " + contentType);
            }
            /* use custom charset or charset from httpconnection */
            String useCS = this.customCharset;
            if (StringUtils.isEmpty(useCS)) {
                useCS = this.httpConnection.getCharset();
            }
            if (StringUtils.isEmpty(useCS)) {
                if (StringUtils.contains(contentType, "application/json")) {
                    // application/json default is UTF-8
                    useCS = "UTF-8";
                }
            }
            if (StringUtils.isEmpty(useCS)) {
                useCS = this.getCharsetFromMetaTags();
            }
            try {
                try {
                    try {
                        if (useCS != null) {
                            /* try to use wanted charset */
                            useCS = useCS.toUpperCase(Locale.ENGLISH);
                            this.htmlCode = new String(this.responseBytes, useCS);
                            if (!keepBytes) {
                                this.responseBytes = null;
                            }
                            this.httpConnection.setCharset(useCS);
                            return this.htmlCode;
                        }
                    } catch (final Exception e) {
                    }
                    this.htmlCode = new String(this.responseBytes, "ISO-8859-1");
                    if (!keepBytes) {
                        this.responseBytes = null;
                    }
                    this.httpConnection.setCharset("ISO-8859-1");
                    return this.htmlCode;
                } catch (final Exception e) {
                    System.out.println("could neither charset: " + useCS + " nor default charset");
                    /* fallback to default charset in error case */
                    this.htmlCode = new String(this.responseBytes);
                    if (!keepBytes) {
                        this.responseBytes = null;
                    }
                    return this.htmlCode;
                }
            } catch (final Exception e) {
                /* in case of error we do not reset byteArray */
                this.httpConnection.setCharset(null);
            }
        }
        return this.htmlCode;
    }

    protected String getHTMLSource() {
        if (!this.requested) {
            return "Request not sent yet";
        }
        try {
            this.getHtmlCode();
            if (StringUtils.isEmpty(this.htmlCode)) {
                final String location = this.getLocation();
                if (location != null) {
                    return "Not HTML Code. Redirect to: " + location;
                }
                return "No htmlCode read";
            }
        } catch (final Throwable e) {
            return "NOTEXT: " + e.getMessage();
        }
        return this.htmlCode;
    }

    public URLConnectionAdapter getHttpConnection() {
        return this.httpConnection;
    }

    /**
     * cached location
     */
    protected String location = null;

    public String getLocation() {
        final String location = this.location;
        if (location == null) {
            if (this.httpConnection != null) {
                final String locationHeader = this.httpConnection.getHeaderField(HTTPConstants.HEADER_RESPONSE_LOCATION);
                if (StringUtils.isEmpty(locationHeader)) {
                    /* check if we have an old-school refresh header */
                    final String refresh = this.httpConnection.getHeaderField("refresh");
                    if (refresh != null) {
                        // we need to filter the time count from the url
                        final String locationRefresh = new Regex(refresh, "url=(.+);?").getMatch(0);
                        this.location = Request.getLocation(locationRefresh, this);
                        return this.location;
                    } else {
                        this.location = "";
                        return null;
                    }
                } else {
                    this.location = Request.getLocation(locationHeader, this);
                    return this.location;
                }
            } else {
                return null;
            }
        } else {
            if (location.length() == 0) {
                return null;
            } else {
                return location;
            }
        }
    }

    /**
     *
     * @since JD2
     * @param locationn
     * @param request
     * @return
     */
    public static String getLocation(final String location, final Request request) {
        if (StringUtils.isEmpty(location)) {
            return null;
        } else {
            try {
                return URLHelper.fixPathTraversal(URLHelper.createURL(location)).toString();
            } catch (final Exception e) {
                if (request != null) {
                    try {
                        return URLHelper.parseLocation(request.getURL(), location);
                    } catch (final Throwable wtf) {
                        return null;
                    }
                }
            }
            return null;
        }
    }

    public HTTPProxy getProxy() {
        return this.proxy;
    }

    public int getReadLimit() {
        return this.readLimit;
    }

    public long getReadTime() {
        return this.readTime;
    }

    public int getReadTimeout() {
        return this.readTimeout;
    }

    public long getRequestTime() {
        return this.httpConnection == null ? -1 : this.httpConnection.getRequestTime();
    }

    /**
     * @return the byteArray
     */
    public byte[] getResponseBytes() {
        return this.responseBytes;
    }

    public String getResponseHeader(final String key) {
        return this.httpConnection == null ? null : this.httpConnection.getHeaderField(key);
    }

    public Map<String, List<String>> getResponseHeaders() {
        return this.httpConnection == null ? null : this.httpConnection.getHeaderFields();
    }

    /**
     * Will replace #getHtmlCode() with next release
     */
    public String getResponseText() throws CharacterCodingException {
        return this.getHtmlCode();
    }

    public String getUrl() {
        try {
            return URLHelper.getURL(this.getURL(), true, false, false).toString();
        } catch (final IOException e) {
            ThrowUncheckedException.throwUncheckedException(e);
            return null;
        }
    }

    public URL getURL() {
        return this.url;
    }

    protected boolean hasCookies() {
        return this.cookies != null && !this.cookies.isEmpty();
    }

    public boolean isContentDecoded() {
        return this.httpConnection == null ? this.isContentDecodedSet() : this.httpConnection.isContentDecoded();
    }

    public Boolean isSSLTrustALL() {
        return this.httpConnection == null ? this.isSSLTrustALLSet() : this.httpConnection.isSSLTrustALL();
    }

    public boolean isContentDecodedSet() {
        return this.contentDecoded;
    }

    public boolean isKeepByteArray() {
        return this.keepByteArray;
    }

    public boolean isRequested() {
        return this.requested;
    }

    protected boolean sendHTTPHeader(HTTPHeader header) {
        return header != null && !StringUtils.isEmpty(header.getKey()) && !StringUtils.isEmpty(header.getValue());
    }

    private void openConnection() throws IOException {
        this.httpConnection = HTTPConnectionFactory.createHTTPConnection(URLHelper.getURL(this.getURL(), true, false, false), this.getProxy());
        this.httpConnection.setRequest(this);
        this.httpConnection.setReadTimeout(this.getReadTimeout());
        this.httpConnection.setConnectTimeout(this.getConnectTimeout());
        this.httpConnection.setContentDecoded(this.isContentDecodedSet());
        final Boolean isSSLTrustALL = this.isSSLTrustALLSet();
        if (isSSLTrustALL != null) {
            this.httpConnection.setSSLTrustALL(isSSLTrustALL);
        }
        final RequestHeader headers = this.getHeaders();
        if (headers != null) {
            for (final HTTPHeader header : headers) {
                if (this.sendHTTPHeader(header)) {
                    this.httpConnection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
        }
        if (this.httpConnection instanceof URLConnectionAdapterDirectImpl) {
            final String connectionRequest = this.httpConnection.getRequestProperty(HTTPConstants.HEADER_REQUEST_CONNECTION);
            if (connectionRequest == null || StringUtils.containsIgnoreCase(connectionRequest, "Keep-Alive")) {
                HTTPConnectionImpl httpConnectionImpl = (HTTPConnectionImpl) this.httpConnection;
                httpConnectionImpl.setKeepAlive(KEEPALIVE.EXTERNAL_EXCEPTION);
            }
        }
        this.preRequest();
        if (this.hasCookies()) {
            final String cookieString = this.getCookieString();
            if (StringUtils.isNotEmpty(cookieString)) {
                this.httpConnection.setRequestProperty("Cookie", cookieString);
            }
        }
    }

    abstract public long postRequest() throws IOException;

    abstract public void preRequest() throws IOException;

    public String printHeaders() {
        if (this.httpConnection == null) {
            return null;
        }
        return this.httpConnection.toString();
    }

    public Request read(final boolean keepByteArray) throws IOException {
        this.keepByteArray = keepByteArray;
        final long tima = System.currentTimeMillis();
        this.httpConnection.setCharset(this.getCustomCharset());
        this.responseBytes = Request.read(this.getHttpConnection(), this.getReadLimit());
        this.readTime = System.currentTimeMillis() - tima;
        return this;
    }

    public void setKeepByteArray(boolean keepByteArray) {
        this.keepByteArray = keepByteArray;
    }

    public void setConnectTimeout(final int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public void setContentDecoded(final boolean c) {
        this.contentDecoded = c;
    }

    public void setCookies(final Cookies cookies) {
        this.cookies = cookies;
    }

    public void setCustomCharset(final String charset) {
        this.customCharset = charset;
    }

    /**
     * DO NOT USE in 09581 Stable
     */
    public void setHeaders(final RequestHeader headers) {
        this.headers = headers;
    }

    public void setHtmlCode(final String htmlCode) {
        this.responseBytes = null;
        this.htmlCode = htmlCode;
        this.requested = true;
    }

    public void setResponseBytes(byte[] bytes) {
        this.responseBytes = bytes;
        this.htmlCode = null;
        this.requested = true;
    }

    public void setProxy(final HTTPProxy proxy) {
        if (proxy == null || proxy instanceof ClonedProxy) {
            this.proxy = proxy;
        } else {
            this.proxy = new ClonedProxy(proxy);
        }
    }

    public void setProxy(final ClonedProxy proxy) {
        this.proxy = proxy;
    }

    public void setReadLimit(final int readLimit) {
        this.readLimit = Math.max(0, readLimit);
    }

    public void setReadTimeout(final int readTimeout) {
        this.readTimeout = readTimeout;
        final URLConnectionAdapter con = this.httpConnection;
        if (con != null) {
            con.setReadTimeout(readTimeout);
        }
    }

    protected void setURL(final URL url) {
        this.url = url;
    }

    @Override
    public String toString() {
        if (!this.requested) {
            return "Request not sent yet";
        }
        final StringBuilder sb = new StringBuilder();
        try {
            if (this.httpConnection != null) {
                sb.append(this.httpConnection.toString());
                sb.append("\r\n");
                this.getHtmlCode();
                sb.append(this.getHTMLSource());
            } else {
                return this.getHTMLSource();
            }
        } catch (final Exception e) {
            return "NOTEXT: " + e.getMessage();
        }
        return sb.toString();
    }

}
