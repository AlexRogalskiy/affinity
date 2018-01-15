/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.affinity.spark

import akka.http.scaladsl.model.StatusCodes.SeeOther
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import io.amient.affinity.avro.AvroSerde
import io.amient.affinity.core.cluster.Node
import io.amient.affinity.core.util.SystemTestBase
import io.amient.affinity.example.graph.message.{Component, VertexProps}
import io.amient.affinity.example.http.handler.{Admin, Graph, PublicApi}
import io.amient.affinity.example.rest.ExampleGatewayRoot
import io.amient.affinity.example.rest.handler.Ping
import io.amient.affinity.kafka.EmbeddedKafka
import io.amient.affinity.stream.BinaryStream
import io.amient.util.spark.CompactRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer._
import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest.{FlatSpec, Matchers}

class AnalyticsSystemTest extends FlatSpec with SystemTestBase with EmbeddedKafka with Matchers {

  override def numPartitions = 4

  val config: Config = ConfigFactory.load("example")
    .withValue(Node.Conf.Affi.Gateway.Http.Host.path, ConfigValueFactory.fromAnyRef("127.0.0.1"))
    .withValue(Node.Conf.Affi.Gateway.Http.Port.path, ConfigValueFactory.fromAnyRef(0))


  val dataNode = new Node(configure(config, Some(zkConnect), Some(kafkaBootstrap)))
  val gatewayNode = new TestGatewayNode(configure(config, Some(zkConnect), Some(kafkaBootstrap)), new ExampleGatewayRoot
    with Ping
    with Admin
    with PublicApi
    with Graph) {
    awaitClusterReady {
      dataNode.startContainer("graph", List(0, 1, 2, 3))
    }
  }

  import gatewayNode._

  http_post(uri("/connect/1/2")).status should be(SeeOther)
  http_post(uri("/connect/3/4")).status should be(SeeOther)
  http_post(uri("/connect/2/3")).status should be(SeeOther)

  override def afterAll(): Unit = {
    try {
      dataNode.shutdown()
      gatewayNode.shutdown()
    } finally {
      super.afterAll()
    }
  }

  "Spark Module" should "be able to see avro-serialised state in kafka" in {

    get_json(http_get(uri("/vertex/1"))).get("data").get("component").getIntValue should be(1)
    get_json(http_get(uri("/vertex/4"))).get("data").get("component").getIntValue should be(1)

    val sc = new SparkContext(new SparkConf()
      .setMaster("local[4]")
      .set("spark.driver.host", "localhost")
      .setAppName("Affinity_Spark_2.0")
      .set("spark.serializer", classOf[KryoSerializer].getName))

    val broadcast = sc.broadcast(configure(config, Some(zkConnect), Some(kafkaBootstrap)))

    val graphRdd = new CompactRDD[Int, VertexProps](
      sc, AvroSerde.create(broadcast.value),
      BinaryStream.bindNewInstance(broadcast.value.getConfig("affinity.keyspace.graph.state.graph.storage.kafka")))

    val sortedGraph = graphRdd.repartition(1).sortByKey().collect().toList
    sortedGraph match {
      case (1, VertexProps(_, 1, _)) ::
        (2, VertexProps(_, 1, _)) ::
        (3, VertexProps(_, 1, _)) ::
        (4, VertexProps(_, 1, _)) :: Nil =>
      case x =>
        throw new AssertionError(s"Graph should contain 4 vertices but was: $x")
    }

    val componentRdd = new CompactRDD[Int, Component](
      sc,
      AvroSerde.create(broadcast.value),
      BinaryStream.bindNewInstance(broadcast.value.getConfig("affinity.keyspace.graph.state.components.storage.kafka")))

    componentRdd.collect.toList match {
      case (1, Component(_, _)) :: Nil =>
      case _ => throw new AssertionError("Graph should contain 1 component")
    }
    val updateBatch: RDD[(Int, Component)] = sc.parallelize(Array((1, null), (2, Component(0L, Set()))))
    componentRdd.update(updateBatch)

    componentRdd.collect.toList match {
      case (2, Component(0L, _)) :: Nil =>
      case _ => throw new AssertionError("Graph should contain 1 component")
    }

  }

}

