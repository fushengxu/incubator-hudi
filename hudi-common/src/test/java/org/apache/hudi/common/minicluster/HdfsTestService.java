/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.minicluster;

import org.apache.hudi.common.model.HoodieTestUtils;
import org.apache.hudi.common.util.FileIOUtils;
import org.apache.hudi.exception.HoodieIOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.Objects;

/**
 * An HDFS minicluster service implementation.
 */
public class HdfsTestService {

  private static final Logger LOG = LogManager.getLogger(HdfsTestService.class);

  /**
   * Configuration settings.
   */
  private Configuration hadoopConf;
  private String workDir;

  /**
   * Embedded HDFS cluster.
   */
  private MiniDFSCluster miniDfsCluster;

  public HdfsTestService() throws IOException {
    workDir = Files.createTempDirectory("temp").toFile().getAbsolutePath();
  }

  public Configuration getHadoopConf() {
    return hadoopConf;
  }

  private static int nextFreePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new HoodieIOException("Unable to find next free port", e);
    }
  }

  public MiniDFSCluster start(boolean format) throws IOException {
    Objects.requireNonNull(workDir, "The work dir must be set before starting cluster.");
    hadoopConf = HoodieTestUtils.getDefaultHadoopConf();

    // If clean, then remove the work dir so we can start fresh.
    String localDFSLocation = getDFSLocation(workDir);
    if (format) {
      LOG.info("Cleaning HDFS cluster data at: " + localDFSLocation + " and starting fresh.");
      File file = new File(localDFSLocation);
      FileIOUtils.deleteDirectory(file);
    }

    int namenodeRpcPort = nextFreePort();
    int datanodePort = nextFreePort();
    int datanodeIpcPort = nextFreePort();
    int datanodeHttpPort = nextFreePort();

    // Configure and start the HDFS cluster
    // boolean format = shouldFormatDFSCluster(localDFSLocation, clean);
    String bindIP = "127.0.0.1";
    configureDFSCluster(hadoopConf, localDFSLocation, bindIP, namenodeRpcPort,
        datanodePort, datanodeIpcPort, datanodeHttpPort);
    miniDfsCluster = new MiniDFSCluster.Builder(hadoopConf).numDataNodes(1).format(format).checkDataNodeAddrConfig(true)
        .checkDataNodeHostConfig(true).build();
    LOG.info("HDFS Minicluster service started.");
    return miniDfsCluster;
  }

  public void stop() {
    LOG.info("HDFS Minicluster service being shut down.");
    miniDfsCluster.shutdown();
    miniDfsCluster = null;
    hadoopConf = null;
  }

  /**
   * Get the location on the local FS where we store the HDFS data.
   *
   * @param baseFsLocation The base location on the local filesystem we have write access to create dirs.
   * @return The location for HDFS data.
   */
  private static String getDFSLocation(String baseFsLocation) {
    return baseFsLocation + Path.SEPARATOR + "dfs";
  }

  /**
   * Configure the DFS Cluster before launching it.
   *
   * @param config The already created Hadoop configuration we'll further configure for HDFS
   * @param localDFSLocation The location on the local filesystem where cluster data is stored
   * @param bindIP An IP address we want to force the datanode and namenode to bind to.
   * @return The updated Configuration object.
   */
  private static Configuration configureDFSCluster(Configuration config, String localDFSLocation, String bindIP,
      int namenodeRpcPort, int datanodePort, int datanodeIpcPort, int datanodeHttpPort) {

    LOG.info("HDFS force binding to ip: " + bindIP);
    config.set(DFSConfigKeys.FS_DEFAULT_NAME_KEY, "hdfs://" + bindIP + ":" + namenodeRpcPort);
    config.set(DFSConfigKeys.DFS_DATANODE_ADDRESS_KEY, bindIP + ":" + datanodePort);
    config.set(DFSConfigKeys.DFS_DATANODE_IPC_ADDRESS_KEY, bindIP + ":" + datanodeIpcPort);
    config.set(DFSConfigKeys.DFS_DATANODE_HTTP_ADDRESS_KEY, bindIP + ":" + datanodeHttpPort);
    // When a datanode registers with the namenode, the Namenode do a hostname
    // check of the datanode which will fail on OpenShift due to reverse DNS
    // issues with the internal IP addresses. This config disables that check,
    // and will allow a datanode to connect regardless.
    config.setBoolean("dfs.namenode.datanode.registration.ip-hostname-check", false);
    config.set("hdfs.minidfs.basedir", localDFSLocation);
    // allow current user to impersonate others
    String user = System.getProperty("user.name");
    config.set("hadoop.proxyuser." + user + ".groups", "*");
    config.set("hadoop.proxyuser." + user + ".hosts", "*");
    return config;
  }

}
