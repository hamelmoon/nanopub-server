package ch.tkuhn.nanopub.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import com.google.gson.Gson;

public class ServerInfo {

	public static ServerInfo load(String serverUrl) throws IOException {
		HttpGet get = new HttpGet(serverUrl);
		get.setHeader("Content-Type", "application/json");
	    InputStream in = HttpClientBuilder.create().build().execute(get).getEntity().getContent();
		return new Gson().fromJson(new InputStreamReader(in), ServerInfo.class);
	}

	private String publicUrl;
	private String admin;
	private boolean postNanopubsEnabled;
	private boolean postPeersEnabled;
	private int maxListSize = 1000;

	public ServerInfo() {
	}

	public ServerInfo(Properties prop) {
		publicUrl = prop.getProperty("public-url");
		admin = prop.getProperty("admin");
		postNanopubsEnabled = Boolean.parseBoolean(prop.getProperty("post-nanopubs-enabled"));
		postPeersEnabled = Boolean.parseBoolean(prop.getProperty("post-peers-enabled"));
		maxListSize = Integer.parseInt(prop.getProperty("maxlistsize"));
	}

	public boolean isPostNanopubsEnabled() {
		return postNanopubsEnabled;
	}

	public boolean isPostPeersEnabled() {
		return postPeersEnabled;
	}

	public int getMaxListSize() {
		return maxListSize;
	}

	public String getPublicUrl() {
		return publicUrl;
	}

	public String getAdmin() {
		return admin;
	}

	public String asJson() {
		return new Gson().toJson(this);
	}

	public List<String> loadPeerList() throws IOException {
		List<String> peerList = new ArrayList<String>();
		HttpGet get = new HttpGet(getPublicUrl() + "peers");
		get.setHeader("Content-Type", "text/plain");
		InputStream in = HttpClientBuilder.create().build().execute(get).getEntity().getContent();
	    BufferedReader r = new BufferedReader(new InputStreamReader(in));
	    String line = null;
	    while ((line = r.readLine()) != null) {
	    	peerList.add(line);
	    }
	    r.close();
		return peerList;
	}

}
