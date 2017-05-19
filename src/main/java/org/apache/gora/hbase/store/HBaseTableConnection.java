/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gora.hbase.store;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread safe implementation to connect to a HBase table.
 *
 */
public class HBaseTableConnection {
  /*
   * The current implementation uses ThreadLocal HTable instances. It keeps
   * track of the floating instances in order to correctly flush and close
   * the connection when it is closed. HBase itself provides a utility called
   * HTablePool for maintaining a tPool of tables, but there are still some
   * drawbacks that are only solved in later releases.
   */
	  public static final Logger LOG = LoggerFactory.getLogger(HBaseTableConnection.class);
  private final Configuration conf;
  private final Connection connection;
  private final RegionLocator regionLocator;
  // BufferedMutator used for doing async flush i.e. autoflush = false
  private final ThreadLocal<ConcurrentLinkedQueue<Mutation>> buffers;
  private final ThreadLocal<Table> tables;

  private final BlockingQueue<Table> tPool = new LinkedBlockingQueue<Table>();
  private final BlockingQueue<ConcurrentLinkedQueue<Mutation>> bPool = new LinkedBlockingQueue<ConcurrentLinkedQueue<Mutation>>();
  @SuppressWarnings("unused")
  private final boolean autoFlush;
  private final TableName tableName;
   final static String KEYTAB_FILE_PATH_KEY = "hbase.keytab.file";
   final static String USER_NAME_KEY = "hbase.kerberos.principal";
  /**
   * Instantiate new connection.
   *
   * @param conf
   * @param tableName
   * @param autoflush
   * @throws IOException
   */
  public HBaseTableConnection(Configuration conf, String tableName, boolean autoflush)
      throws IOException {
    this.conf = conf;

    this.tables = new ThreadLocal<Table>();
    this.buffers = new ThreadLocal<ConcurrentLinkedQueue<Mutation>>();
    this.connection = makeKerberosConnection(conf);
    this.tableName = TableName.valueOf(tableName);
    this.regionLocator = this.connection.getRegionLocator(this.tableName);

    this.autoFlush = autoflush;
  }
  static Connection makeKerberosConnection( Configuration config) throws IOException {
		String keytab = config.get(HBaseTableConnection.KEYTAB_FILE_PATH_KEY);
	      String principal = config.get(HBaseTableConnection.USER_NAME_KEY);
	      	UserGroupInformation.setConfiguration(config);
	      	UserGroupInformation loginUser = UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytab);
//	        Connection kerberosConnection =
	      	LOG.info("kerberosConnection: "+(keytab==null || principal==null));
	      	final Configuration finalconfig =config;
	      	if(keytab==null || principal==null){
	      		return ConnectionFactory.createConnection(config);
	      	}else{
	      		return loginUser.doAs(new PrivilegedAction<Connection>() {

				@Override
				public Connection run() {
					try {
						return    ConnectionFactory.createConnection(finalconfig);
					} catch (IOException e) {
						
						e.printStackTrace();
						return null;
					}
				}
			});
	      	}
	      
	}
  private Table getTable() throws IOException {
    Table table = tables.get();
    if (table == null) {
      table = connection.getTable(tableName);
      tPool.add(table); //keep track
      tables.set(table);
    }
    return table;
  }

  private ConcurrentLinkedQueue<Mutation> getBuffer() throws IOException {
    ConcurrentLinkedQueue<Mutation> buffer = buffers.get();
    if (buffer == null) {
      buffer = new ConcurrentLinkedQueue<Mutation>();
      bPool.add(buffer);
      buffers.set(buffer);
    }
    return buffer;
  }

  public void flushCommits() throws IOException {
    BufferedMutator bufMutator = connection.getBufferedMutator(this.tableName);
    for (ConcurrentLinkedQueue<Mutation> buffer : bPool) {
      while (!buffer.isEmpty()) {
        Mutation m = buffer.poll();
        bufMutator.mutate(m);
      }
    }
    bufMutator.flush();
    bufMutator.close();
  }

  public void close() throws IOException {
    // Flush and close all instances.
    // (As an extra safeguard one might employ a shared variable i.e. 'closed'
    //  in order to prevent further table creation but for now we assume that
    //  once close() is called, clients are no longer using it).
    flushCommits();

    for (Table table : tPool) {
      table.close();
    }
  }

  public Configuration getConfiguration() {
    return conf;
  }

  /**
   * getStartEndKeys provided by {@link HRegionLocation}.
   * @see RegionLocator#getStartEndKeys()
   */
  public Pair<byte[][], byte[][]> getStartEndKeys() throws IOException {
    return regionLocator.getStartEndKeys();
  }
  /**
   * getRegionLocation provided by {@link HRegionLocation}
   * @see RegionLocator#getRegionLocation(byte[])
   */
  public HRegionLocation getRegionLocation(final byte[] bs) throws IOException {
    return regionLocator.getRegionLocation(bs);
  }

  public boolean exists(Get get) throws IOException {
    return getTable().exists(get);
  }

  public boolean[] existsAll(List<Get> list) throws IOException {
    return getTable().existsAll(list);
  }

  public Result get(Get get) throws IOException {
    return getTable().get(get);
  }

  public Result[] get(List<Get> gets) throws IOException {
    return getTable().get(gets);
  }

  public ResultScanner getScanner(Scan scan) throws IOException {
    return getTable().getScanner(scan);
  }

  public void put(Put put) throws IOException {
    getBuffer().add(put);
  }

  public void put(List<Put> puts) throws IOException {
    getBuffer().addAll(puts);
  }

  public void delete(Delete delete) throws IOException {
    getBuffer().add(delete);
  }

  public void delete(List<Delete> deletes) throws IOException {
    getBuffer().addAll(deletes);
  }

  public TableName getName() {
    return tableName;
  }
}
