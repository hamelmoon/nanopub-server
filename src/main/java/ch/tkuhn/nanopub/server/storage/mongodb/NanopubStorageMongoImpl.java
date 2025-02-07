package ch.tkuhn.nanopub.server.storage.mongodb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import ch.tkuhn.nanopub.server.ServerConf;
import ch.tkuhn.nanopub.server.ServerInfo;
import ch.tkuhn.nanopub.server.Utils;
import ch.tkuhn.nanopub.server.storage.NanopubStorage;
import ch.tkuhn.nanopub.server.exceptions.NanopubDbException;
import ch.tkuhn.nanopub.server.exceptions.NotTrustyNanopubException;
import ch.tkuhn.nanopub.server.exceptions.OversizedNanopubException;
import ch.tkuhn.nanopub.server.exceptions.ProtectedNanopubException;
import com.github.jsonldjava.shaded.com.google.common.base.Strings;
import com.google.inject.Singleton;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.rdf4j.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.NanopubWithNs;
import org.nanopub.extra.server.ServerInfo.ServerInfoException;
import org.nanopub.trusty.TrustyNanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.gridfs.GridFSInputFile;

import net.trustyuri.TrustyUriUtils;

/**
 * Class that connects to MongoDB. Each nanopub server instance needs its own DB (but this is not
 * checked).
 *
 * @author Tobias Kuhn
 */
@Singleton
public class NanopubStorageMongoImpl implements NanopubStorage {
	private Logger logger = LoggerFactory.getLogger(this.getClass());

	// Use trig internally to keep namespaces:
	private static RDFFormat internalFormat = RDFFormat.TRIG;

	private ServerConf conf;
	private MongoClient mongo;
	private DB db;
	private GridFS packageGridFs;
	private JournalMongoImpl journalMongoImpl;

	public NanopubStorageMongoImpl() {
		init();
	}

	private void init() {
		logger.info("Initialize new DB object");
		conf = new ServerConf();
		ServerAddress serverAddress = new ServerAddress(conf.getMongoDbHost(), conf.getMongoDbPort());
		List<MongoCredential> credentials = new ArrayList<>();
		if (conf.getMongoDbUsername() != null) {
			credentials.add(MongoCredential.createCredential(
					conf.getMongoDbUsername(),
					conf.getMongoDbName(),
					conf.getMongoDbPassword().toCharArray()));
		}
		mongo = new MongoClient(serverAddress, credentials);
		db = mongo.getDB(conf.getMongoDbName());
		packageGridFs = new GridFS(db, "packages_gzipped");

		for (String s : conf.getInitialPeers()) {
			addPeerToCollection(s);
		}
		journalMongoImpl = new JournalMongoImpl(db);
	}
	@Override
	public JournalMongoImpl getJournal() {
		return journalMongoImpl;
	}

//	private MongoClient getMongoClient() {
//		return mongo;
//	}

	private static DBObject pingCommand = new BasicDBObject("ping", "1");

	@Override
	public boolean isAccessible() {
		try {
			db.command(pingCommand);
		} catch (MongoException ex) {
			return false;
		}
		return true;
	}

	private DBCollection getNanopubCollection() {
		return db.getCollection("nanopubs");
	}

	@Override
	public Nanopub getNanopub(String artifactCode) {
		BasicDBObject query = new BasicDBObject("_id", artifactCode);
		DBCursor cursor = getNanopubCollection().find(query);
		if (!cursor.hasNext()) {
			return null;
		}
		String nanopubString = cursor.next().get("nanopub").toString();
		Nanopub np = null;
		try {
			np = new NanopubImpl(nanopubString, internalFormat);
		} catch (MalformedNanopubException ex) {
			throw new RuntimeException("Stored nanopub is not wellformed (this shouldn't happen)", ex);
		} catch (RDF4JException ex) {
			throw new RuntimeException("Stored nanopub is corrupted (this shouldn't happen)", ex);
		}
		if (ServerConf.get().isCheckNanopubsOnGetEnabled() && !TrustyNanopubUtils.isValidTrustyNanopub(np)) {
			throw new RuntimeException("Stored nanopub is not trusty (this shouldn't happen)");
		}
		return np;
	}

	@Override
	public boolean hasNanopub(String artifactCode) {
		BasicDBObject query = new BasicDBObject("_id", artifactCode);
		return getNanopubCollection().find(query).hasNext();
	}

	@Override
	public synchronized void loadNanopub(Nanopub np) throws NotTrustyNanopubException,
			OversizedNanopubException, NanopubDbException, ProtectedNanopubException {
		if (np instanceof NanopubWithNs) {
			((NanopubWithNs) np).removeUnusedPrefixes();
		}
		if (!TrustyNanopubUtils.isValidTrustyNanopub(np)) {
			throw new NotTrustyNanopubException(np);
		}
		if (!ServerConf.get().isRunAsLocalServerEnabled() && Utils.isProtectedNanopub(np)) {
			throw new ProtectedNanopubException(np);
		}
		ServerInfo info = ServerConf.getInfo();
		if (info.getMaxNanopubTriples() != null && np.getTripleCount() > info.getMaxNanopubTriples()) {
			throw new OversizedNanopubException(np);
		}
		if (info.getMaxNanopubBytes() != null && np.getByteCount() > info.getMaxNanopubBytes()) {
			throw new OversizedNanopubException(np);
		}
		if (isFull()) {
			throw new NanopubDbException("Server is full (maximum number of nanopubs reached)");
		}
		String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().toString());
		String npString = null;
		try {
			npString = NanopubUtils.writeToString(np, internalFormat);
		} catch (RDFHandlerException ex) {
			throw new RuntimeException("Unexpected exception when processing nanopub", ex);
		}
		BasicDBObject id = new BasicDBObject("_id", artifactCode);
		BasicDBObject dbObj = new BasicDBObject("_id", artifactCode).append("nanopub", npString).append("uri", np.getUri().toString());
		DBCollection coll = getNanopubCollection();
		if (!coll.find(id).hasNext()) {
			journalMongoImpl.checkNextNanopubNo();
			long currentPageNo = journalMongoImpl.getCurrentPageNo();
			String pageContent = journalMongoImpl.getPageContent(currentPageNo);
			pageContent += np.getUri() + "\n";
			// TODO Implement proper transactions, rollback, etc.
			// The following three lines of code are critical. If Java gets interrupted
			// in between, the data will remain in a slightly inconsistent state (but, I
			// think, without serious consequences).
			journalMongoImpl.increaseNextNanopubNo();
			// If interrupted here, the current page of the journal will miss one entry
			// (e.g. contain only 999 instead of 1000 entries).
			journalMongoImpl.setPageContent(currentPageNo, pageContent);
			// If interrupted here, journal will contain an entry that cannot be found in
			// the database. This entry might be loaded later and then appear twice in the
			// journal.
			coll.insert(dbObj);
		}
		String[] postUrls = ServerConf.get().getPostUrls();
		for (String postUrl : postUrls) {
			if (!Strings.isNullOrEmpty(postUrl)) {
				int failedTries = 0;
				boolean success = false;
				while (!success && failedTries < 3) {
					try {
						HttpPost post = new HttpPost(postUrl);
						post.setHeader("Content-Type", internalFormat.getDefaultMIMEType());
						post.setEntity(new StringEntity(npString, "UTF-8"));
						HttpResponse response = HttpClientBuilder.create().build().execute(post);
						if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
							success = true;
						} else {
							logger.error("Failed to post nanopub to " + postUrl + ": " + response.getStatusLine().getReasonPhrase());
							failedTries++;
						}
					} catch (Exception ex) {
						logger.error("Error while posting nanopub", ex);
						failedTries++;
					}
					if (!success) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException ex) {}
					}
				}
			}
		}
		if (ServerConf.get().isLogNanopubLoadingEnabled()) {
			logger.info("Nanopub loaded: " + np.getUri());
		}
	}

	private DBCollection getPeerCollection() {
		return db.getCollection("peers");
	}

	@Override
	public List<String> getPeerUris() {
		List<String> peers = new ArrayList<String>();
		DBCursor cursor = getPeerCollection().find();
		while (cursor.hasNext()) {
			peers.add(cursor.next().get("_id").toString());
		}
		return peers;
	}

	@Override
	public void addPeer(String peerUrl) throws ServerInfoException {
		ServerInfo.load(peerUrl);  // throw exception if something is wrong
		addPeerToCollection(peerUrl);
	}

	private void addPeerToCollection(String peerUrl) {
		if (peerUrl.equals(ServerConf.getInfo().getPublicUrl())) {
			return;
		}
		DBCollection coll = getPeerCollection();
		BasicDBObject dbObj = new BasicDBObject("_id", peerUrl);
		if (!coll.find(dbObj).hasNext()) {
			coll.insert(dbObj);
		}
	}

	@Override
	public void updatePeerState(ServerInfo peerInfo, long npno) {
		String url = peerInfo.getPublicUrl();
		BasicDBObject q = new BasicDBObject("_id", url);
		long jid = peerInfo.getJournalId();
		BasicDBObject update = new BasicDBObject("_id", url).append("journalId", jid).append("nextNanopubNo", npno);
		getPeerCollection().update(q, update);
	}

	@Override
	public Pair<Long,Long> getLastSeenPeerState(String peerUrl) {
		BasicDBObject q = new BasicDBObject("_id", peerUrl);
		Long journalId = null;
		Long nextNanopubNo = null;
		DBObject r = getPeerCollection().find(q).next();
		if (r.containsField("journalId")) journalId = Long.parseLong(r.get("journalId").toString());
		if (r.containsField("nextNanopubNo")) nextNanopubNo = Long.parseLong(r.get("nextNanopubNo").toString());
		if (journalId == null || nextNanopubNo == null) return null;
		return Pair.of(journalId, nextNanopubNo);
	}

	@Override
	public void populatePackageCache() throws IOException {
		long c = journalMongoImpl.getCurrentPageNo();
		for (long page = 1; page < c; page++) {
			if (!isPackageCached(page)) {
				writePackageToStream(page, false, new NullOutputStream());
			}
		}
	}


	private boolean isPackageCached(long pageNo) {
		return packageGridFs.findOne(pageNo + "") != null;
	}

	@Override
	public void writePackageToStream(long pageNo, boolean gzipped, OutputStream out) throws IOException {
		if (pageNo < 1 || pageNo >= journalMongoImpl.getCurrentPageNo()) {
			throw new IllegalArgumentException("Not a complete page: " + pageNo);
		}
		GridFSDBFile f = packageGridFs.findOne(pageNo + "");
		OutputStream packageOut = null;
		InputStream packageAsStream = null;
		try {
			if (f == null) {
				if (gzipped) {
					out = new GZIPOutputStream(out);
				}
				ByteArrayOutputStream bOut = new ByteArrayOutputStream();
				packageOut = new GZIPOutputStream(bOut);
				String pageContent = journalMongoImpl.getPageContent(pageNo);
				for (String uri : pageContent.split("\\n")) {
					Nanopub np = getNanopub(TrustyUriUtils.getArtifactCode(uri));
					String s;
					try {
						s = NanopubUtils.writeToString(np, RDFFormat.TRIG);
					} catch (RDFHandlerException ex) {
						throw new RuntimeException("Unexpected RDF handler exception", ex);
					}
					byte[] bytes = (s + "\n").getBytes();
					out.write(bytes);
					packageOut.write(bytes);
				}
				packageOut.close();
				packageAsStream = new ByteArrayInputStream(bOut.toByteArray());
				GridFSInputFile i = packageGridFs.createFile(packageAsStream);
				i.setFilename(pageNo + "");
				i.save();
			} else {
				if (gzipped) {
					f.writeTo(out);
				} else {
					GZIPInputStream in = new GZIPInputStream(f.getInputStream());
					byte[] buffer = new byte[1024];
					int len;
					while ((len = in.read(buffer)) > 0) {
						out.write(buffer, 0, len);
					}
					in.close();
				}
			}
		} finally {
			if (out != null) out.close();
			if (packageOut != null) packageOut.close();
			if (packageAsStream != null) packageAsStream.close();
		}
	}

	@Override
	public synchronized long getNextNanopubNo() {
		return journalMongoImpl.getNextNanopubNo();
	}

	@Override
	public boolean isFull() {
		ServerInfo info = ServerConf.getInfo();
		return (info.getMaxNanopubs() != null && journalMongoImpl.getNextNanopubNo() >= info.getMaxNanopubs());
	}

	@Override
	public String getCid(String artifactCode){
		return null;
	}

	@Override
	public String testPublish(Nanopub np) {
		long artifactMock = Instant.now().getEpochSecond();
		String npString = null;
		try {
			npString = NanopubUtils.writeToString(np, internalFormat);
		} catch (RDFHandlerException ex) {
			throw new RuntimeException("Unexpected exception when processing nanopub", ex);
		}
		BasicDBObject id = new BasicDBObject("_id", artifactMock);
		BasicDBObject dbObj = new BasicDBObject("_id", artifactMock).append("nanopub", npString).append("uri", np.getUri().toString());
		DBCollection coll = db.getCollection("nanopubMock");
		if (!coll.find(id).hasNext()) {
			coll.insert(dbObj);
		}
		return String.valueOf(coll.getCount());
	}


}
