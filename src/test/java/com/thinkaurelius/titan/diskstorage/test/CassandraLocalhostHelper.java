package com.thinkaurelius.titan.diskstorage.test;

import com.thinkaurelius.titan.DiskgraphTest;
import com.thinkaurelius.titan.core.GraphDatabaseFactory;
import com.thinkaurelius.titan.diskstorage.cassandra.CassandraThriftStorageManager;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.core.GraphDatabase;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnection;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnectionFactory;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnectionPool;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.*;

public class CassandraLocalhostHelper {
	private Process cassandraProcess;
	private Future<?> cassandraOutputLoggerFuture;
	private CassandraOutputReader outputReader;
	private Thread cassandraKiller;
	private boolean delete = true;
	
	private final ExecutorService cassandraOutputLogger =
		Executors.newSingleThreadExecutor();
	private boolean logCassandraOutput = true;
	private final String address;
	private final String cassandraConfigDir;
	private final String cassandraDataDir;
	private final String cassandraInclude;
	
	private static final String cassandraCommand = "cassandra";
	private static final int port =
            CassandraThriftStorageManager.DEFAULT_PORT;
	private static final Logger log =
		LoggerFactory.getLogger(CassandraLocalhostHelper.class);
	private static final long CASSANDRA_STARTUP_TIMEOUT = 10000L;
	
	public CassandraLocalhostHelper(String address) {
		this.address = address;
		
		cassandraDataDir = StringUtils.join(new String[] { "target",
				"cassandra-tmp", "workdir", address }, File.separator);
		
		cassandraConfigDir = StringUtils.join(new String[] { "target",
				"cassandra-tmp", "conf", address }, File.separator);
		
		cassandraInclude = cassandraConfigDir + File.separator
				+ "cassandra.in.sh";
		
		// We rely on maven to provide config files ahead of test execution
		assert(new File(cassandraConfigDir).isDirectory());
		assert(new File(cassandraInclude).isFile());
	}
	
	public CassandraLocalhostHelper() {
		this("127.0.0.1");
	}
	
	public CassandraLocalhostHelper setDelete(boolean delete) {
		this.delete = delete;
		return this;
	}
	
	public CassandraLocalhostHelper setLogging(boolean logging) {
		logCassandraOutput = logging;
		return this;
	}
	
	public boolean getLogging() {
		return logCassandraOutput;
	}
	
	public void startCassandra() {
		try {
			final File cdd = new File(cassandraDataDir);
			
			if (delete) {
				if (cdd.isDirectory()) { 
					log.debug("Deleting dir {}...", cassandraDataDir);
					FileUtils.deleteQuietly(new File(cassandraDataDir));
					log.debug("Deleted dir {}", cassandraDataDir);
				} else if (cdd.isFile()) {
					log.debug("Deleting file {}...", cassandraDataDir);
					cdd.delete();
					log.debug("Deleted file {}", cassandraDataDir);
				} else {
					log.debug("Cassandra data directory {} does not exist; " +
							"letting Cassandra startup script create it", 
							cassandraDataDir);
				}
			}
			
			ProcessBuilder pb = new ProcessBuilder(cassandraCommand, "-f");
			Map<String, String> env = pb.environment();
			env.put("CASSANDRA_CONF", cassandraConfigDir);
			env.put("CASSANDRA_INCLUDE", cassandraInclude);
			pb.redirectErrorStream(true);
			// Start Cassandra
			log.debug("Starting Cassandra process {}...", 
					StringUtils.join(pb.command(), ' '));
			cassandraProcess = pb.start();
			log.debug("Started Cassandra process {}.", 
					StringUtils.join(pb.command(), ' '));
			// Register a Cassandra-killer shutdown hook
			cassandraKiller = new CassandraKiller(cassandraProcess, address + ":" + port);
			Runtime.getRuntime().addShutdownHook(cassandraKiller);
			
			// Create Runnable to process Cassandra's stderr and stdout
			log.debug("Starting Cassandra output handler task...");
			outputReader = new CassandraOutputReader(cassandraProcess,
					address, port, logCassandraOutput);
			cassandraOutputLoggerFuture =
				cassandraOutputLogger.submit(outputReader);
			log.debug("Started Cassandra output handler task.");
			
			// Block in a loop until connection to the Thrift port succeeds
			long sleep = 0;
			long connectAttemptStartTime = System.currentTimeMillis();
			final long sleepGrowthIncrement = 100;
			log.debug(
					"Attempting to connect to Cassandra's Thrift service on {}:{}...",
					address, port);
			while (!Thread.currentThread().isInterrupted()) {
				Socket s = new Socket();
				s.setSoTimeout(50);
				try {
					s.connect(new InetSocketAddress(address, port));
					long delay = System.currentTimeMillis() -
						connectAttemptStartTime;
					log.debug("Thrift connection to {}:{} succeeded " +
							"(about {} ms after process start)",
							new Object[]{ address, port, delay });
					break;
				} catch (IOException e) {
					sleep += sleepGrowthIncrement;
					log.debug("Thrift connection failed; retrying in {} ms",
							sleep);
					Thread.sleep(sleep);
				} finally {
					if (s.isConnected()) {
						s.close();
						log.debug("Closed Thrift connection to {}:{}",
								address, port);
					}
				}
			}
			
			/* 
			 * Check that the Cassandra process logged that it
			 * successfully bound its Thrift port.
			 * 
			 * This detects
			 */
			log.debug("Waiting for Cassandra process to log successful Thrift-port bind...");
			if (!outputReader.awaitThrift(CASSANDRA_STARTUP_TIMEOUT, TimeUnit.MILLISECONDS)) {
				String msg = "Cassandra process failed to bind Thrift-port within timeout.";
				log.error(msg);
				throw new RuntimeException(msg);
			}
			log.debug("Cassandra process logged successful Thrift-port bind.");
			
			/*
			 * Destroy any pooled Thrift connections to this
			 * Cassandra instance.  This isn't necessary in
			 * normal operation, but in testing, where maven may
			 * destroy and recreate a Cassandra cluster many
			 * times without restarting the JVM, failure to
			 * destroy stale connections can cause odd failures.
			 */
			log.debug("Clearing pooled Thrift connections for {}:{}",
					address, port);
			CTConnectionPool.getPool(address, port, CassandraThriftStorageManager.DEFAULT_THRIFT_TIMEOUT_MS).clear();
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	public void stopCassandra() {
		try {
			if (null != cassandraOutputLoggerFuture) {
				cassandraOutputLoggerFuture.cancel(true);
				try {
					cassandraOutputLoggerFuture.get();
				} catch (CancellationException e) { }
				cassandraOutputLoggerFuture = null;
			}
			if (null != cassandraProcess) {
				cassandraProcess.destroy();
				cassandraProcess.waitFor();
				cassandraProcess = null;
			}
			if (null != cassandraKiller) {
				Runtime.getRuntime().removeShutdownHook(cassandraKiller);
				cassandraKiller = null;
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	public GraphDatabase openDatabase() {
        // Open graph database against the cassandra daemon
		return GraphDatabaseFactory.open(getConfiguration());
	}
	
	public Configuration getConfiguration() {
        Configuration config = new BaseConfiguration().subset(GraphDatabaseConfiguration.STORAGE_NAMESPACE);
        config.addProperty(GraphDatabaseConfiguration.STORAGE_DIRECTORY_KEY,DiskgraphTest.homeDir);
        config.addProperty(CassandraThriftStorageManager.PROP_HOSTNAME,address);
        config.addProperty(CassandraThriftStorageManager.PROP_SELF_HOSTNAME,address);
        config.addProperty(CassandraThriftStorageManager.PROP_TIMEOUT,5*60000);
		return config;
	}

    public static Configuration getLocalStorageConfiguration() {
        Configuration config = new BaseConfiguration();
        config.addProperty(GraphDatabaseConfiguration.STORAGE_DIRECTORY_KEY,DiskgraphTest.homeDir);
        config.addProperty(CassandraThriftStorageManager.PROP_HOSTNAME,"127.0.0.1");
        config.addProperty(CassandraThriftStorageManager.PROP_TIMEOUT,10000);
        return config;
    }

	public void waitForClusterSize(int minSize) throws InterruptedException {
		CTConnectionFactory f = CTConnectionPool.getFactory(address, port, CassandraThriftStorageManager.DEFAULT_THRIFT_TIMEOUT_MS);
		CTConnection conn = null;
		try {
			conn = f.makeRawConnection();
			CTConnectionFactory.waitForClusterSize(conn.getClient(), minSize);
		} catch (TTransportException e) {
			throw new RuntimeException(e);
		} finally {
			if (null != conn)
				if (conn.getTransport().isOpen())
					conn.getTransport().close();
		}
	}
}

class CassandraOutputReader implements Runnable {
	private final Process p;
	private final boolean logAllCassandraOutput;
	private final String address;
	private final int port;
	private final Logger log;
	
	private static final String THRIFT_BOUND_LOGLINE =
		"Listening for thrift clients...";
	
	private final CountDownLatch thriftBound = new CountDownLatch(1);

	CassandraOutputReader(Process p, String address, int port, boolean logAllCassandraOutput) {
		this.p = p;
		this.address = address;
		this.port = port;
		this.logAllCassandraOutput = logAllCassandraOutput;
		log = LoggerFactory.getLogger("Cassandra:" + address.replace('.', '-') + ":"+ port);
	}
	
	public boolean awaitThrift(long timeout, TimeUnit units) throws InterruptedException {
		return thriftBound.await(timeout, units);
	}

	@Override
	public void run() {
		InputStream is = p.getInputStream();
		String prefix = null;
		prefix = "[" + address + ":" + port + "]";
		try {
			BufferedReader br = new BufferedReader(
					new InputStreamReader(is));
			while (!Thread.currentThread().isInterrupted()) {
				String l = br.readLine();
				if (null == l)
					break;
				if (l.endsWith(THRIFT_BOUND_LOGLINE))
					thriftBound.countDown();
				if (logAllCassandraOutput)
					log.debug("{} {}", prefix, l);
			}
			log.debug("Shutdown by interrupt");
		} catch (IOException e) {
			log.debug("IOException: {}", e.getMessage());
		} catch (Exception e) {
			log.debug("Terminated by Exception", e);
		}
	}
}

class CassandraKiller extends Thread {
	private final Process cassandra;
	private final String cassandraDesc;

	CassandraKiller(Process cassandra, String cassandraDesc) {
		this.cassandra = cassandra;
		this.cassandraDesc = cassandraDesc;
	}
	
	@Override
	public void run() {
		System.err.println("Terminating Cassandra " + cassandraDesc + "...");
		cassandra.destroy();
	}
}
