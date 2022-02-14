package ch.tkuhn.nanopub.server;

import ch.tkuhn.nanopub.server.storage.Journal;
import ch.tkuhn.nanopub.server.storage.NanopubStorageFactory;
import com.google.inject.Singleton;

import java.util.Properties;

@Singleton
public class ServerInfo extends org.nanopub.extra.server.ServerInfo {

	private static final long serialVersionUID = 3460590224836603269L;

	public static ServerInfo load(String serverUrl) throws ServerInfoException {
		return (ServerInfo) load(serverUrl, ServerInfo.class);
	}

	private transient boolean loadFromDb = false;


	public ServerInfo(Properties prop) {
		protocolVersion = NanopubServerUtils.protocolVersion;
		publicUrl = prop.getProperty("public.url");
		admin = prop.getProperty("admin");
		postNanopubsEnabled = Boolean.parseBoolean(prop.getProperty("post.nanopubs.enabled"));
		postPeersEnabled = Boolean.parseBoolean(prop.getProperty("post.peers.enabled"));
		description = "nanopub-server " + prop.getProperty("version") + ", " + prop.getProperty("build.date");
		try {
			maxNanopubTriples = Integer.parseInt(prop.getProperty("max.nanopub.triples"));
		} catch (Exception ex) {}
		try {
			maxNanopubBytes = Long.parseLong(prop.getProperty("max.nanopub.bytes"));
		} catch (Exception ex) {}
		try {
			maxNanopubs = Long.parseLong(prop.getProperty("max.nanopubs"));
		} catch (Exception ex) {}
		loadFromDb = true;
	}

	@Override
	public int getPageSize() {
		if (loadFromDb) {
			pageSize = NanopubStorageFactory.getInstance().getJournal().getPageSize();
		}
		return super.getPageSize();
	}

	@Override
	public long getNextNanopubNo() {
		if (loadFromDb) {
			nextNanopubNo = NanopubStorageFactory.getInstance().getNextNanopubNo();
		}
		return super.getNextNanopubNo();
	}

	@Override
	public long getJournalId() {
		if (loadFromDb) {
			journalId = NanopubStorageFactory.getInstance().getJournal().getJournalId();
		}
		return super.getJournalId();
	}

	@Override
	public String getUriPattern() {
		if (loadFromDb) {
			uriPattern = NanopubStorageFactory.getInstance().getJournal().getUriPattern();
		}
		return super.getUriPattern();
	}

	@Override
	public String getHashPattern() {
		if (loadFromDb) {
			hashPattern = NanopubStorageFactory.getInstance().getJournal().getHashPattern();
		}
		return super.getHashPattern();
	}

	@Override
	public String asJson() {
		if (loadFromDb) {
			Journal j = NanopubStorageFactory.getInstance().getJournal();
			nextNanopubNo = j.getNextNanopubNo();
			pageSize = j.getPageSize();
			journalId = j.getJournalId();
			uriPattern = j.getUriPattern();
			hashPattern = j.getHashPattern();
		}
		return super.asJson();
	}

}
