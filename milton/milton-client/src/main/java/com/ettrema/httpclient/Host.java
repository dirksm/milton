package com.ettrema.httpclient;

import com.bradmcevoy.common.Path;
import com.bradmcevoy.http.Range;
import com.bradmcevoy.http.Response;
import com.ettrema.cache.Cache;
import com.ettrema.cache.MemoryCache;

import com.ettrema.common.LogUtils;
import com.ettrema.httpclient.Utils.CancelledException;
import com.ettrema.httpclient.zsyncclient.ZSyncClient;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.OptionsMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author mcevoyb
 */
public class Host extends Folder {

    private static String PROPFIND_XML = "<?xml version=\"1.0\"?>"
            + "<d:propfind xmlns:d='DAV:' xmlns:c='clyde'><d:prop>"
            + "<d:resourcetype/><d:displayname/><d:getcontentlength/><d:creationdate/><d:getlastmodified/><d:iscollection/><d:lockdiscovery/>"
            + "<d:quota-available-bytes/><d:quota-used-bytes/><c:crc/>"
            + "</d:prop></d:propfind>";
    private static String LOCK_XML = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
            + "<D:lockinfo xmlns:D='DAV:'>"
            + "<D:lockscope><D:exclusive/></D:lockscope>"
            + "<D:locktype><D:write/></D:locktype>"
            + "<D:owner>${owner}</D:owner>"
            + "</D:lockinfo>";
    private static final Logger log = LoggerFactory.getLogger(Host.class);
    public final String server;
    public final int port;
    public final String user;
    public final String password;
    public final String rootPath;
    /**
     * time in milliseconds to be used for all timeout parameters
     */
    private int timeout;
    private final HttpClient client;
    private final TransferService transferService;
    private final ZSyncClient zSyncClient;
    private final List<ConnectionListener> connectionListeners = new ArrayList<ConnectionListener>();
    private String propFindXml = PROPFIND_XML;

    static {
//    System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
//    System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
//    System.setProperty("org.apache.commons.logging.simplelog.log.httpclient.wire.header", "debug");
//    System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.commons.httpclient", "debug");    
    }

    public Host(String server, int port, String user, String password, ProxyDetails proxyDetails) {
        this(server, null, port, user, password, proxyDetails, 30000, null, false);
    }

    public Host(String server, int port, String user, String password, ProxyDetails proxyDetails, Cache<Folder, List<Resource>> cache) {
        this(server, null, port, user, password, proxyDetails, 30000, cache, false); // defaul timeout of 30sec
    }

    public Host(String server, String rootPath, int port, String user, String password, ProxyDetails proxyDetails, Cache<Folder, List<Resource>> cache) {
        this(server, rootPath, port, user, password, proxyDetails, 30000, cache, false); // defaul timeout of 30sec
    }

    public Host(String server, String rootPath, int port, String user, String password, ProxyDetails proxyDetails, int timeoutMillis, Cache<Folder, List<Resource>> cache, boolean enableZSync) {
        super((cache != null ? cache : new MemoryCache<Folder, List<Resource>>("resource-cache-default", 50, 20)));
        if (server == null) {
            throw new IllegalArgumentException("host name cannot be null");
        }
        this.rootPath = rootPath;
        this.timeout = timeoutMillis;
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
        client = new HttpClient();
        client.getHttpConnectionManager().getParams().setConnectionTimeout(10000);
        if (user != null) {
            client.getState().setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, password));
        }

        if (user != null && user.length() > 0) {
            client.getParams().setAuthenticationPreemptive(true);
        }
        client.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
        client.getParams().setSoTimeout(timeoutMillis);
        client.getParams().setConnectionManagerTimeout(timeoutMillis);
        if (proxyDetails != null) {
            if (proxyDetails.isUseSystemProxy()) {
                System.setProperty("java.net.useSystemProxies", "true");
            } else {
                System.setProperty("java.net.useSystemProxies", "false");
                if (proxyDetails.getProxyHost() != null && proxyDetails.getProxyHost().length() > 0) {
                    HostConfiguration hostConfig = client.getHostConfiguration();
                    hostConfig.setProxy(proxyDetails.getProxyHost(), proxyDetails.getProxyPort());
                    if (proxyDetails.hasAuth()) {
                        client.getState().setProxyCredentials(AuthScope.ANY, new UsernamePasswordCredentials(proxyDetails.getUserName(), proxyDetails.getPassword()));
                    }
                }
            }
        }
        transferService = new TransferService(client, connectionListeners);
        transferService.setTimeout(timeoutMillis);
        if (enableZSync) {
            zSyncClient = new ZSyncClient(transferService);
        } else {
            zSyncClient = null;
        }
    }

    /**
     * Finds the resource by iterating through the path parts resolving
     * collections as it goes. If any path component is not founfd returns null
     *
     * @param path
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public Resource find(String path) throws IOException, com.ettrema.httpclient.HttpException {
        return find(path, false);
    }

    public Resource find(String path, boolean invalidateCache) throws IOException, com.ettrema.httpclient.HttpException {
        if (path == null || path.length() == 0 || path.equals("/")) {
            return this;
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] arr = path.split("/");
        return _find(this, arr, 0, invalidateCache);
    }

    public static Resource _find(Folder parent, String[] arr, int i, boolean invalidateCache) throws IOException, com.ettrema.httpclient.HttpException {
        String childName = arr[i];
        if (invalidateCache) {
            parent.flush();
        }
        Resource child = parent.child(childName);
        if (i == arr.length - 1) {
            return child;
        } else {
            if (child instanceof Folder) {
                return _find((Folder) child, arr, i + 1, invalidateCache);
            } else {
                return null;
            }
        }
    }

    public Folder getFolder(String path) throws IOException, com.ettrema.httpclient.HttpException {
        Resource res = find(path);
        if (res instanceof Folder) {
            return (Folder) res;
        } else {
            throw new RuntimeException("Not a folder: " + res.href());
        }
    }

    /**
     *
     * @param newUri - must be fully qualified and correctly encoded
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doMkCol(String newUri) throws com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        MkColMethod p = new MkColMethod(newUri);
        try {
            int result = host().client.executeMethod(p);
            if (result == 409) {
                // probably means the folder already exists
                return result;
            }
            Utils.processResultCode(result, newUri);
            return result;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            p.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     * Returns the lock token, which must be retained to unlock the resource
     *
     * @param uri - must be encoded
     * @param owner
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized String doLock(String uri) throws com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        LockMethod p = new LockMethod(uri);
        try {
            String lockXml = LOCK_XML.replace("${owner}", user);
            RequestEntity requestEntity = new StringRequestEntity(lockXml, null, "UTF-8");
            p.setRequestEntity(requestEntity);
            int result = host().client.executeMethod(p);
            Utils.processResultCode(result, uri);
            return p.getLockToken();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            p.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param uri - must be encoded
     * @param lockToken
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doUnLock(String uri, String lockToken) throws com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        UnLockMethod p = new UnLockMethod(uri, lockToken);
        try {
            int result = host().client.executeMethod(p);
            Utils.processResultCode(result, uri);
            return result;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            p.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param path - an Un-encoded path. Eg /a/b c/ = /a/b%20c/
     * @param content
     * @param contentLength
     * @param contentType
     * @return
     */
    public int doPut(Path path, InputStream content, Long contentLength, String contentType) {
        String dest = buildEncodedUrl(path);
        return doPut(dest, content, contentLength, contentType, null);
    }

    /**
     *
     * @param newUri
     * @param file
     * @param listener
     * @return - the result code
     * @throws FileNotFoundException
     * @throws HttpException
     */
    public int doPut(Path remotePath, java.io.File file, ProgressListener listener) throws FileNotFoundException, HttpException {
        if (zSyncClient != null) {
            try {
                int bytes = zSyncClient.upload(this, file, remotePath, listener);
                LogUtils.trace(log, "doPut: uploaded: ", bytes, " bytes");
                return Response.Status.SC_OK.code;
            } catch (NotFoundException e) {
                // ZSync file was not found
                log.trace("Not found: " + remotePath);
            } catch (IOException ex) {
                throw new HttpException("Exception doing zsync upload", ex);
            }
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            String dest = buildEncodedUrl(remotePath);
            return doPut(dest, in, file.length(), null, listener);
        } finally {
            IOUtils.closeQuietly(in);
        }

    }

    /**
     * Uploads the data. Does not do any file syncronisation
     *
     * @param newUri
     * @param content
     * @param contentLength
     * @param contentType
     * @return - the result code
     */
    public synchronized int doPut(String newUri, InputStream content, Long contentLength, String contentType, ProgressListener listener) {
        LogUtils.trace(log, "doPut", newUri);
        return transferService.put(newUri, content, contentLength, contentType, listener);
    }

    /**
     *
     * @param from - encoded source url
     * @param newUri - encoded destination
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doCopy(String from, String newUri) throws com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        CopyMethod m = new CopyMethod(from, newUri);
        try {
            int res = host().client.executeMethod(m);
            Utils.processResultCode(res, from);
            return res;
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            m.releaseConnection();
            notifyFinishRequest();
        }

    }

    /**
     *
     * @param url - encoded url
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doDelete(String url) throws IOException, com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        DeleteMethod m = new DeleteMethod(url);
        try {
            int res = host().client.executeMethod(m);
            Utils.processResultCode(res, url);
            return res;
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } finally {
            m.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param sourceUrl - encoded source url
     * @param newUri - encoded destination url
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized int doMove(String sourceUrl, String newUri) throws IOException, com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        MoveMethod m = new MoveMethod(sourceUrl, newUri);
        try {
            int res = host().client.executeMethod(m);
            Utils.processResultCode(res, sourceUrl);
            return res;
        } finally {
            m.releaseConnection();
            notifyFinishRequest();
        }

    }

    /**
     *
     * @param url - the encuded URL to query
     * @param depth - depth to generate responses for. Zero means only the
     * specified url, 1 means it and its direct children, etc
     * @return
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized List<PropFindMethod.Response> doPropFind(String url, int depth) throws IOException, com.ettrema.httpclient.HttpException {
        log.trace("doPropFind: " + url);
        notifyStartRequest();
        PropFindMethod m = new PropFindMethod(url);
        m.addRequestHeader(new Header("Depth", depth + ""));
        m.setDoAuthentication(true);

        try {
            if (propFindXml != null) {
                RequestEntity requestEntity = new StringRequestEntity(propFindXml, "text/xml", "UTF-8");
                m.setRequestEntity(requestEntity);
            }

            int res = client.executeMethod(m);
            Utils.processResultCode(res, url);
            if (res == 207) {
                return m.getResponses();
            } else {
                return null;
            }
        } catch (NotFoundException e) {
            log.trace("not found: " + url);
            return Collections.EMPTY_LIST;
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } finally {
            m.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param url - fully qualified and encoded URL
     * @param receiver
     * @param rangeList - if null does a normal GET request
     * @throws com.ettrema.httpclient.HttpException
     * @throws com.ettrema.httpclient.Utils.CancelledException
     */
    public synchronized void doGet(String url, StreamReceiver receiver, List<Range> rangeList, ProgressListener listener) throws com.ettrema.httpclient.HttpException, Utils.CancelledException {
        transferService.get(url, receiver, rangeList, listener);
    }

    public synchronized void doGet(Path path, final java.io.File file, ProgressListener listener) throws IOException, NotFoundException, com.ettrema.httpclient.HttpException {
        LogUtils.trace(log, "doGet", path);
        if (zSyncClient != null) {
            zSyncClient.download(this, path, file, listener);
        } else {
            String url = this.buildEncodedUrl(path);
            transferService.get(url, new StreamReceiver() {

                @Override
                public void receive(InputStream in) throws IOException {
                    OutputStream out = null;
                    BufferedOutputStream bout = null;
                    try {
                        out = FileUtils.openOutputStream(file);
                        bout = new BufferedOutputStream(out);
                        IOUtils.copy(in, bout);
                        bout.flush();
                    } finally {
                        IOUtils.closeQuietly(bout);
                        IOUtils.closeQuietly(out);
                    }

                }
            }, null, listener);
        }
    }

    /**
     *
     * @param path - encoded path, but not fully qualified. Must not be prefixed
     * with a slash, as it will be appended to the host's URL
     * @throws java.net.ConnectException
     * @throws Unauthorized
     * @throws UnknownHostException
     * @throws SocketTimeoutException
     * @throws IOException
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized void options(String path) throws java.net.ConnectException, Unauthorized, UnknownHostException, SocketTimeoutException, IOException, com.ettrema.httpclient.HttpException {
        String url = this.encodedUrl() + path;
        doOptions(url);
    }

    private synchronized void doOptions(String url) throws NotFoundException, java.net.ConnectException, Unauthorized, java.net.UnknownHostException, SocketTimeoutException, IOException, com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        String uri = url;
        log.trace("doOptions: {}", url);
        OptionsMethod m = new OptionsMethod(uri);
        InputStream in = null;
        try {
            int res = client.executeMethod(m);
            log.trace("result code: " + res);
            if (res == 301 || res == 302) {
                return;
            }
            Utils.processResultCode(res, url);
        } finally {
            Utils.close(in);
            m.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     * Retrieve the bytes at the specified path.
     *
     * @param path - encoded but not fully qualified. Must NOT be slash prefixed
     * as it will be appended to the host's url
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public synchronized byte[] get(String path) throws com.ettrema.httpclient.HttpException {
        String url = this.encodedUrl() + path;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            transferService.get(url, new StreamReceiver() {

                @Override
                public void receive(InputStream in) {
                    try {
                        IOUtils.copy(in, out);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }, null, null);
        } catch (CancelledException ex) {
            throw new RuntimeException("Should never happen because no progress listener is set", ex);
        }
        return out.toByteArray();
    }

    /**
     * POSTs the variables and returns the body
     *
     * @param url - fully qualified and encoded URL to post to
     * @param params
     * @return
     */
    public String doPost(String url, Map<String, String> params) throws com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        PostMethod m = new PostMethod(url);
        for (Entry<String, String> entry : params.entrySet()) {
            m.addParameter(entry.getKey(), entry.getValue());
        }
        InputStream in = null;
        try {
            int res = client.executeMethod(m);
            Utils.processResultCode(res, url);
            in = m.getResponseBodyAsStream();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            IOUtils.copy(in, bout);
            return bout.toString();
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            Utils.close(in);
            m.releaseConnection();
            notifyFinishRequest();
        }
    }

    /**
     *
     * @param url - fully qualified and encoded
     * @param params
     * @param parts
     * @return
     * @throws com.ettrema.httpclient.HttpException
     */
    public String doPost(String url, Map<String, String> params, Part[] parts) throws com.ettrema.httpclient.HttpException {
        notifyStartRequest();
        PostMethod filePost = new PostMethod(url);
        if (params != null) {
            for (Entry<String, String> entry : params.entrySet()) {
                filePost.addParameter(entry.getKey(), entry.getValue());
            }
        }
        filePost.setRequestEntity(new MultipartRequestEntity(parts, filePost.getParams()));

        InputStream in = null;
        try {
            int res = client.executeMethod(filePost);
            Utils.processResultCode(res, url);
            in = filePost.getResponseBodyAsStream();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            IOUtils.copy(in, bout);
            return bout.toString();
        } catch (HttpException ex) {
            throw new RuntimeException(ex);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            Utils.close(in);
            filePost.releaseConnection();
            notifyFinishRequest();
        }
    }

    @Override
    public Host host() {
        return this;
    }

    @Override
    public String href() {
        String s = "http://" + server;
        if (this.port != 80 && this.port > 0) {
            s += ":" + this.port;
        }

        if (rootPath != null && rootPath.length() > 0) {
            if (!rootPath.startsWith("/")) {
                s += "/";
            }
            s = s + rootPath;
        } else {
            s += "/";
        }
        if (!s.endsWith("/")) {
            s += "/";
        }
        return s;
    }

    /**
     * Returns the fully qualified URL for the given path
     *
     * @param path
     * @return
     */
    public String getHref(Path path) {
        String s = href();

        if (!path.isRelative()) {
            s = s.substring(0, s.length() - 1);
        }
        //log.trace("host href: " + s);
        return s + path; // path will be absolute
    }

    @Override
    public String encodedUrl() {
        return href(); // for a Host, there are no un-encoded components (eg rootPath, if present, must be encoded)
    }

    public static String urlEncode(String s) {
//        if( rootPath != null ) {
//            s = rootPath + s;
//        }
        return urlEncodePath(s);
    }

    public static String urlEncodePath(String s) {
        try {
            org.apache.commons.httpclient.URI uri = new URI(s, false);
            s = uri.toString();
            s = s.replace("&", "%26");
            return s;
        } catch (URIException ex) {
            throw new RuntimeException(s, ex);
        } catch (NullPointerException ex) {
            throw new RuntimeException(s, ex);
        }
        //s = s.replace( " ", "%20" );
    }

    public String getPropFindXml() {
        return propFindXml;
    }

    public void setPropFindXml(String propFindXml) {
        this.propFindXml = propFindXml;
    }

    public com.ettrema.httpclient.Folder getOrCreateFolder(Path remoteParentPath, boolean create) throws com.ettrema.httpclient.HttpException, IOException {
        log.trace("getOrCreateFolder: {}", remoteParentPath);
        com.ettrema.httpclient.Folder f = this;
        if (remoteParentPath != null) {
            for (String childName : remoteParentPath.getParts()) {
                if (childName.equals("_code")) {
                    f = new Folder(f, childName, cache);
                } else {
                    com.ettrema.httpclient.Resource child = f.child(childName);
                    if (child == null) {
                        if (create) {
                            f = f.createFolder(childName);
                        } else {
                            return null;
                        }
                    } else if (child instanceof com.ettrema.httpclient.Folder) {
                        f = (com.ettrema.httpclient.Folder) child;
                    } else {
                        log.warn("Can't upload. A resource exists with the same name as a folder, but is a file: " + remoteParentPath + " - " + child.getClass());
                        return null;
                    }
                }

            }
        }
        return f;
    }

    /**
     * @return the timeout
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * @param timeout the timeout to set
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
        transferService.setTimeout(timeout);
    }

    public ZSyncClient getzSyncClient() {
        return zSyncClient;
    }

    private void notifyStartRequest() {
        for (ConnectionListener l : connectionListeners) {
            l.onStartRequest();
        }
    }

    private void notifyFinishRequest() {
        for (ConnectionListener l : connectionListeners) {
            l.onFinishRequest();
        }
    }

    public void addConnectionListener(ConnectionListener e) {
        connectionListeners.add(e);
    }

    public String buildEncodedUrl(Path path) {
        String url = this.encodedUrl();
        String[] arr = path.getParts();
        for (int i = 0; i < arr.length; i++) {
            String s = arr[i];
            if (i > 0) {
                url += "/";
            }
            url += com.bradmcevoy.http.Utils.percentEncode(s);
        }
        return url;
    }
}
