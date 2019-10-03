package com.datastax.spark.connector.cluster

import java.net.InetSocketAddress

import com.datastax.bdp.hadoop.hive.metastore.CassandraClientConfiguration
import com.datastax.spark.connector.ccm.CcmConfig
import com.datastax.spark.connector.cql.CassandraConnectorConf._
import com.datastax.spark.connector.cql.DefaultAuthConfFactory
import org.slf4j.MDC

/** Provides cluster(s) defined by one of the sub-traits. */
trait ClusterProvider {

  def cluster: Cluster

  def cluster(clusterNo: Int): Cluster
}

/** Encapsulates cluster configuration. Sub-traits provide a configuration objects that define clusters.
  *
  * Integration tests that share the same fixture, share the same process during execution.
  *
  * Callers are purposefully denied an ability to configure clusters on spot. Creating many different configurations
  * would result in additional time wasted for bootstrapping clusters (as every configuration needs cluster bootstrap
  * and eventually teardown). Desired usage pattern is utilizing one of the following predefined fixtures.
  *
  * For the same reason, please think twice before adding another [[Fixture]] sub-trait. */
sealed trait Fixture extends ClusterProvider {

  /** Each test group utilizes a fixture. Fixtures define one or more clusters with one or more nodes each.
    * Each node is bootstrapped with different bind address in a form of 127.x.y.z, where x - test group number,
    * y - cluster number within x test group, z - node number within y cluster.
    * This property is set by test framework and defines the "127.x" part of node's address. */
  protected[cluster] def ipPrefix: String = sys.env.getOrElse("CCM_IP_PREFIX", "127.0.")

  protected[cluster] def groupNumber: Int = sys.env.getOrElse("TEST_GROUP_NO", "0").toInt

  /* Integration tests logs are prefixed with [Tx] to denote which test group/process produced the given log line */
  MDC.put("TEST_GROUP_NO", groupNumber.toString)

  sys.env.get("DSE_HOME").foreach { home =>
    System.setProperty("dse", "true")
    System.setProperty("ccm.directory", home)
    System.setProperty("ccm.version", "6.8")
    System.setProperty("ccm.branch", "master")
  }

  private[cluster] def configs: Seq[CcmConfig]

  private[cluster] def connectionParameters(address: InetSocketAddress): Map[String, String]
}

sealed trait SingleClusterFixture extends Fixture {
  override def cluster: Cluster = ClusterHolder.get(this).head

  override def cluster(c: Int): Cluster = if (c == 0) cluster else
    throw new IllegalArgumentException(s"This provider defines only one cluster, cluster $c does not exist")

  protected val defaultConfig: CcmConfig = CcmConfig(
    ipPrefix = ipPrefix + "0.",
    jmxPortOffset = CcmConfig.MAX_NUMBER_OF_NODES * groupNumber)
}

/** Most of the integration tests use this cluster configuration. It has no auth configured. Reuse this cluster config
  * whenever is is possible. */
trait DefaultCluster extends SingleClusterFixture {

  private[cluster] final override val configs: Seq[CcmConfig] = Seq(defaultConfig)

  private[cluster] override def connectionParameters(address: InetSocketAddress): Map[String, String] =
    DefaultCluster.defaultConnectionParameters(address)
}

object DefaultCluster {
  def defaultConnectionParameters(address: InetSocketAddress): Map[String, String] = {
    val host = address.getAddress.getHostAddress
    val port = address.getPort.toString
    Map(
      ConnectionHostParam.name -> host,
      ConnectionPortParam.name -> port,
      s"spark.hadoop.${CassandraClientConfiguration.CONF_PARAM_HOST}" -> host,
      s"spark.hadoop.${CassandraClientConfiguration.CONF_PARAM_NATIVE_PORT}" -> port
    )
  }
}

/** SSL enabled cluster configuration. It has no auth configured. */
trait SSLCluster extends SingleClusterFixture {

  private val keystorePath = CcmConfig.storeResource(
    prefix = groupNumber.toString,
    resource = CcmConfig.DEFAULT_SERVER_KEYSTORE_PATH)

  private[cluster] final override val configs: Seq[CcmConfig] = Seq(
    defaultConfig.withSsl(
      keystorePath.toString, CcmConfig.DEFAULT_SERVER_KEYSTORE_PASSWORD
    ))

  private[cluster] override def connectionParameters(address: InetSocketAddress): Map[String, String] =
    DefaultCluster.defaultConnectionParameters(address) ++
    SSLCluster.defaultConnectionParameters()
}

object SSLCluster {
  def defaultConnectionParameters(): Map[String, String] = {
    Map(
      SSLEnabledParam.name -> "true",
      SSLTrustStorePasswordParam.name -> CcmConfig.DEFAULT_CLIENT_TRUSTSTORE_PASSWORD,
      SSLTrustStorePathParam.name -> CcmConfig.DEFAULT_CLIENT_TRUSTSTORE_FILE.getPath,
      SSLClientAuthEnabledParam.name -> "true",
      SSLKeyStorePasswordParam.name -> CcmConfig.DEFAULT_CLIENT_KEYSTORE_PASSWORD,
      SSLKeyStorePathParam.name -> CcmConfig.DEFAULT_CLIENT_KEYSTORE_FILE.getPath
    )
  }
}

/** SSL enabled cluster configuration with auth configured. */
trait AuthCluster extends SingleClusterFixture {

  private val keystorePath = CcmConfig.storeResource(
    prefix = groupNumber.toString,
    resource = CcmConfig.DEFAULT_SERVER_KEYSTORE_PATH)

  private val truststorePath = CcmConfig.storeResource(
    prefix = groupNumber.toString,
    resource = CcmConfig.DEFAULT_SERVER_TRUSTSTORE_PATH)

  private[cluster] final override val configs: Seq[CcmConfig] = {
    val sslConf = defaultConfig.withSslAuth(
      keystorePath, CcmConfig.DEFAULT_SERVER_KEYSTORE_PASSWORD,
      truststorePath, CcmConfig.DEFAULT_SERVER_TRUSTSTORE_PASSWORD
    )
    Seq(sslConf.copy(dseConfiguration = sslConf.dseConfiguration ++ Map(
      "authentication_options.enabled" -> "true"
    )))
  }

  private[cluster] override def connectionParameters(address: InetSocketAddress): Map[String, String] =
    DefaultCluster.defaultConnectionParameters(address) ++
    SSLCluster.defaultConnectionParameters() ++
    AuthCluster.defaultConnectionParameters()
}

object AuthCluster {
  def defaultConnectionParameters(): Map[String, String] = {
    Map(
      DefaultAuthConfFactory.UserNameParam.name -> "cassandra",
      DefaultAuthConfFactory.PasswordParam.name -> "cassandra"
    )
  }
}

/** A fixture that bootstraps two separate clusters with one node each. */
trait TwoClustersWithOneNode extends Fixture {

  private[cluster] final override val configs: Seq[CcmConfig] = for (i <- 0 to 1) yield
    CcmConfig(
      ipPrefix = s"$ipPrefix$i.",
      jmxPortOffset = CcmConfig.MAX_NUMBER_OF_NODES * groupNumber + i)

  private[cluster] override def connectionParameters(address: InetSocketAddress): Map[String, String] =
    DefaultCluster.defaultConnectionParameters(address)

  override def cluster: Cluster = cluster(0)

  override def cluster(c: Int): Cluster = ClusterHolder.get(this)(c)
}

trait CETCluster extends DefaultCluster

trait CSTCluster extends DefaultCluster

trait PSTCluster extends DefaultCluster

/** Fixture marker that instructs test framework to execute the marked test within a separated process/JVM. */
trait SeparateJVM extends Fixture