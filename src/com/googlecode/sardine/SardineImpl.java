package com.googlecode.sardine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.StatusLine;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.impl.auth.DigestScheme;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;

import com.googlecode.sardine.auth.NTLMSchemeFactory;
import com.googlecode.sardine.model.Creationdate;
import com.googlecode.sardine.model.Getcontentlength;
import com.googlecode.sardine.model.Getcontenttype;
import com.googlecode.sardine.model.Getlastmodified;
import com.googlecode.sardine.model.Multistatus;
import com.googlecode.sardine.model.Prop;
import com.googlecode.sardine.model.Response;
import com.googlecode.sardine.util.SardineException;
import com.googlecode.sardine.util.SardineUtil;
import com.googlecode.sardine.util.SardineUtil.HttpCopy;
import com.googlecode.sardine.util.SardineUtil.HttpMkCol;
import com.googlecode.sardine.util.SardineUtil.HttpMove;
import com.googlecode.sardine.util.SardineUtil.HttpPropFind;

/**
 * Implementation of the Sardine interface. This
 * is where the meat of the Sardine library lives.
 *
 * @author jonstevens
 */
public class SardineImpl implements Sardine
{
	/** */
	Factory factory;

	/** */
	DefaultHttpClient client;

	/** was a username/password passed in? */
	boolean authEnabled;
	final DigestScheme md5Auth = new DigestScheme();
	final Map<String, Header> cache = new HashMap<String, Header>();

	/** */
	public SardineImpl(Factory factory) throws SardineException
	{
		this(factory, null, null, null, null);
	}

	/** */
	public SardineImpl(Factory factory, String username, String password) throws SardineException
	{
		this(factory, username, password, null, null);
	}

	/**
	 * Main constructor.
	 */
	public SardineImpl(Factory factory, String username, String password, SSLSocketFactory sslSocketFactory, HttpRoutePlanner routePlanner) throws SardineException
	{
		this.factory = factory;

		String host = System.getProperty("http.proxyHost");
		String user = System.getProperty("http.proxyUser");
		int n = user == null?-1:user.indexOf("\\");
		String domain = n == -1?System.getProperty("http.auth.ntlm.domain"):user.substring(0, n);
		String proxy_username = n == -1?user:user.substring(n + 1);
		String proxy_password = System.getProperty("http.proxyPassword");
		HttpParams params = new BasicHttpParams();
		ConnManagerParams.setMaxTotalConnections(params, 100);
		HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
		HttpProtocolParams.setUserAgent(params, "Sardine/" + Version.getSpecification());

		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		if (sslSocketFactory != null)
			schemeRegistry.register(new Scheme("https", sslSocketFactory, 443));
		else
			schemeRegistry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));

		ClientConnectionManager cm = new ThreadSafeClientConnManager(params, schemeRegistry);
		this.client = new DefaultHttpClient(cm, params);

		// for proxy configurations
		if (routePlanner != null)
			this.client.setRoutePlanner(routePlanner);

		if (domain != null)
		{
			// register NTLM authentication factory
			this.client.getAuthSchemes().register("ntlm", new NTLMSchemeFactory());
			 //  use NTLM credentials
			this.client.getCredentialsProvider().setCredentials(
			new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, AuthScope.ANY_REALM, "ntlm"),
			new NTCredentials(proxy_username, proxy_password, host, domain));
		}

		if ((username != null) && (password != null))
		{
			// use standard authentications (BASIC or DIGEST)
			this.client.getCredentialsProvider().setCredentials(
	                new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, AuthScope.ANY_REALM, "basic"),
			new UsernamePasswordCredentials(username, password));
			
			this.authEnabled = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#getResources(java.lang.String)
	 */
	public List<DavResource> getResources(String url) throws SardineException
	{
		HttpPropFind propFind = new HttpPropFind(url);
		propFind.setEntity(SardineUtil.getResourcesEntity());

		HttpResponse response = this.executeWrapper(propFind);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			propFind.abort();
			throw new SardineException("Failed to get resources. Is the url valid?", url,
					statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}

		// Process the response from the server.
		Multistatus multistatus = SardineUtil.getMulitstatus(this.factory.getUnmarshaller(), response, url);

		List<Response> responses = multistatus.getResponse();

		List<DavResource> resources = new ArrayList<DavResource>(responses.size());

		// Are we getting a directory listing or not?
		// the path after the host stuff
		int firstSlash = url.indexOf('/', 8);
		String baseUrl = null;
		if (url.endsWith("/"))
			baseUrl = url.substring(firstSlash);

		// Get the part of the url from the start to the first slash
		// ie: http://server.com
		String hostPart = url.substring(0, firstSlash);

		for (Response resp : responses)
		{
			boolean currentDirectory = false;
			boolean isDirectory = false;

			String href = resp.getHref().get(0);

			// figure out the name of the file and set
			// the baseUrl if it isn't already set (like when
			// we are looking for just one file)
			String name = null;
			if (baseUrl != null)
			{
				// Some (broken) servers don't return a href with a trailing /
				if ((href.length() == baseUrl.length() - 1) && baseUrl.endsWith("/"))
				{
					href += "/";
				}

				if (href.startsWith(hostPart))
				{
					name = href.substring(hostPart.length() + baseUrl.length());
				}
				else
					name = href.substring(baseUrl.length());

				if ("".equals(name) || (name.length() == 0))
				{
					// This is the directory itself.
					isDirectory = true;
					currentDirectory = true;
				}
			}
			else
			{
				// figure out the name of the file
				int last = href.lastIndexOf("/") + 1;
				name = href.substring(last);

				// this is the part after the host, but without the file
				baseUrl = href.substring(0, last);
			}

			// Ignore crap files
			if (name.equals(".DS_Store"))
				continue;

			// Remove the final / from the name for directories
			if (name.endsWith("/"))
			{
				name = name.substring(0, name.length() - 1);
				isDirectory = true;
			}

			Prop prop = resp.getPropstat().get(0).getProp();

			String creationdate = null;
			Creationdate gcd = prop.getCreationdate();
			if ((gcd != null) && (gcd.getContent().size() == 1))
				creationdate = gcd.getContent().get(0);

			// modifieddate is sometimes not set
			// if that's the case, use creationdate
			String modifieddate = null;
			Getlastmodified glm = prop.getGetlastmodified();
			if ((glm != null) && (glm.getContent().size() == 1))
				modifieddate = glm.getContent().get(0);
			else
				modifieddate = creationdate;

			String contentType = null;
			Getcontenttype gtt = prop.getGetcontenttype();
			if ((gtt != null) && (gtt.getContent().size() == 1))
				contentType = gtt.getContent().get(0);

			// Make sure that directories have the correct content type.
			if (isDirectory && (contentType == null))
			{
				// Need to correct the contentType to identify as a directory.
				contentType = "httpd/unix-directory";
			}

			String contentLength = "0";
			Getcontentlength gcl = prop.getGetcontentlength();
			if ((gcl != null) && (gcl.getContent().size() == 1))
				contentLength = gcl.getContent().get(0);

			DavResource dr = new DavResource(hostPart + baseUrl, name, SardineUtil.parseDate(creationdate),
					SardineUtil.parseDate(modifieddate), contentType, Long.valueOf(contentLength), currentDirectory);

			resources.add(dr);
		}
		return resources;
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#getInputStream(java.lang.String)
	 */
	public InputStream getInputStream(String url) throws SardineException
	{
		HttpGet get = new HttpGet(url);

		HttpResponse response = this.executeWrapper(get);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			get.abort();
			throw new SardineException(url, statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}

		try
		{
			return response.getEntity().getContent();
		}
		catch (IOException ex)
		{
			get.abort();
			throw new SardineException(ex);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#put(java.lang.String, byte[])
	 */
	public void put(String url, byte[] data) throws SardineException
	{
		HttpPut put = new HttpPut(url);

		ByteArrayEntity entity = new ByteArrayEntity(data);
		put.setEntity(entity);

		HttpResponse response = this.executeWrapper(put);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			put.abort();
			throw new SardineException(url, statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#put(java.lang.String, InputStream)
	 */
	public void put(String url, InputStream dataStream) throws SardineException
	{
		HttpPut put = new HttpPut(url);

		// A length of -1 means "go until end of stream"
		InputStreamEntity entity = new InputStreamEntity(dataStream, -1);
		put.setEntity(entity);

		HttpResponse response = this.executeWrapper(put);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			put.abort();
			throw new SardineException(url, statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#delete(java.lang.String)
	 */
	public void delete(String url) throws SardineException
	{
		HttpDelete delete = new HttpDelete(url);

		HttpResponse response = this.executeWrapper(delete);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			delete.abort();
			throw new SardineException(url, statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#move(java.lang.String, java.lang.String)
	 */
	public void move(String sourceUrl, String destinationUrl) throws SardineException
	{
		HttpMove move = new HttpMove(sourceUrl, destinationUrl);

		HttpResponse response = this.executeWrapper(move);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			move.abort();
			throw new SardineException("sourceUrl: " + sourceUrl + ", destinationUrl: " + destinationUrl,
					statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#copy(java.lang.String, java.lang.String)
	 */
	public void copy(String sourceUrl, String destinationUrl)
		throws SardineException
	{
		HttpCopy copy = new HttpCopy(sourceUrl, destinationUrl);

		HttpResponse response = this.executeWrapper(copy);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			copy.abort();
			throw new SardineException("sourceUrl: " + sourceUrl + ", destinationUrl: " + destinationUrl,
					statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#createDirectory(java.lang.String)
	 */
	public void createDirectory(String url) throws SardineException
	{
		HttpMkCol mkcol = new HttpMkCol(url);
		HttpResponse response = this.executeWrapper(mkcol);

		StatusLine statusLine = response.getStatusLine();
		if (!SardineUtil.isGoodResponse(statusLine.getStatusCode()))
		{
			mkcol.abort();
			throw new SardineException(url, statusLine.getStatusCode(), statusLine.getReasonPhrase());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see com.googlecode.sardine.Sardine#exists(java.lang.String)
	 */
	public boolean exists(String url) throws SardineException
	{
		HttpHead head = new HttpHead(url);

		HttpResponse response = this.executeWrapper(head);

		StatusLine statusLine = response.getStatusLine();

		return SardineUtil.isGoodResponse(statusLine.getStatusCode());
	}

	/**
	 * Small wrapper around HttpClient.execute() in order to wrap the IOException
	 * into a SardineException.
	 */
	private HttpResponse executeWrapper(HttpRequestBase base) throws SardineException
	{
		try
		{
			if (this.authEnabled) {
				if (cache.containsKey(base.getMethod())) {
					base.setHeader(cache.get(base.getMethod()));
					return this.client.execute(base);
				} else {
					final HttpRequestBase request = (HttpRequestBase)base.clone();
					final HttpResponse response = this.client.execute(request);

					if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
						final Credentials creds = this.client.getCredentialsProvider().getCredentials(AuthScope.ANY);
						Header header;

						if (response.containsHeader("WWW-Authenticate")) {
							md5Auth.processChallenge(response.getHeaders("WWW-Authenticate")[0]);
							header = md5Auth.authenticate(creds, base);
						} else {
							header = new BasicHeader("Authorization", "Basic " + new String(Base64.encodeBase64(new String(creds.getUserPrincipal().getName() + ":" + creds.getPassword()).getBytes())));
						}
						request.abort();
						base.setHeader(header);
						cache.put(base.getMethod(), header);
						return this.client.execute(base);
					} else {
						return response;
					}
				}
			} else {
				return this.client.execute(base);
			}
		}
		catch (Exception ex)
		{
			base.abort();
			throw new SardineException(ex);
		}
	}
}
