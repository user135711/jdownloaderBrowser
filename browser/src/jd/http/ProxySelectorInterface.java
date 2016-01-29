package jd.http;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.appwork.utils.net.httpconnection.HTTPProxy;

public interface ProxySelectorInterface {

    List<HTTPProxy> getProxiesByURI(URI uri);

    /**
     * the selector may request a new connection for the given url, or ask for proxy auth information. if there is a new connection option
     * afterwards, it should return true, else false.
     *
     * @param request
     * @param retryCounter
     * @return true if the http backend should try the request again
     */
    boolean updateProxy(Request request, int retryCounter);

    boolean reportConnectException(Request request, int retryCounter, IOException e);

}
