package io.amient.affinity.systemtests.kafka

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import io.amient.affinity.avro.AvroSerde
import io.amient.affinity.avro.schema.ZkAvroSchemaRegistry
import io.amient.affinity.core.storage.State
import io.amient.affinity.core.storage.kafka.KafkaStorage
import io.amient.affinity.core.util.SystemTestBase
import io.amient.affinity.kafka.{EmbeddedKafka, KafkaAvroDeserializer}
import io.amient.affinity.systemtests.{KEY, TestRecord, UUID}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class KafkaEcosystemTest extends FlatSpec with SystemTestBase with EmbeddedKafka with Matchers {

  override def numPartitions: Int = 2

  val config = configure(ConfigFactory.load("systemtests")
    .withValue(AvroSerde.CONFIG_PROVIDER_CLASS, ConfigValueFactory.fromAnyRef(classOf[ZkAvroSchemaRegistry].getName))
    , Some(zkConnect), Some(kafkaBootstrap))

  val system = ActorSystem.create("ConfluentEcoSystem", config)

  import system.dispatcher

  override def beforeAll: Unit = {
    SerializationExtension(system)
  }

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }


  private def createStateStoreForPartition(name: String, stateStoreConfig: Config)(implicit partition: Int) = {
    new State[Int, TestRecord](name, system, stateStoreConfig)
  }

  behavior of "KafkaDeserializer"

  it should "be able to work with ZkAvroSchemaRegistry" in {
    config.getString(AvroSerde.CONFIG_PROVIDER_CLASS) should be (classOf[ZkAvroSchemaRegistry].getName)
    system.settings.config.getString(AvroSerde.CONFIG_PROVIDER_CLASS) should be (classOf[ZkAvroSchemaRegistry].getName)

    val stateStoreName = "throughput-test"
    val stateStoreConfig = config.getConfig(State.CONFIG_STATE_STORE(stateStoreName))
    val topic = stateStoreConfig.getString(KafkaStorage.CONFIG_KAFKA_TOPIC)
    val state = createStateStoreForPartition(stateStoreName, stateStoreConfig)(0)
    val numWrites = new AtomicInteger(10)
    val numToWrite = numWrites.get
    val l = System.currentTimeMillis()
    val updates = Future.sequence(for (i <- (1 to numToWrite)) yield {
      state.update(i, TestRecord(KEY(i), UUID.random, System.currentTimeMillis(), s"test value $i")) transform(
        (s) => s, (e: Throwable) => {
        numWrites.decrementAndGet()
        e
      })
    })
    Await.ready(updates, 10 seconds)
    println(s"written ${numWrites.get} records of state data in ${System.currentTimeMillis() - l} ms")
    state.size should equal(numWrites.get)

    val consumerProps = Map(
      "bootstrap.servers" -> kafkaBootstrap,
      "group.id" -> "group2",
      "auto.offset.reset" -> "earliest",
      "max.poll.records" -> 1000,
      "key.deserializer" -> classOf[KafkaAvroDeserializer].getName,
      "value.deserializer" -> classOf[KafkaAvroDeserializer].getName,
      AvroSerde.CONFIG_PROVIDER_CLASS -> classOf[ZkAvroSchemaRegistry].getName,
      ZkAvroSchemaRegistry.CONFIG_ZOOKEEPER_CONNECT -> zkConnect
    )

    val consumer = new KafkaConsumer[Int, TestRecord](consumerProps.mapValues(_.toString.asInstanceOf[AnyRef]))

    consumer.subscribe(List(topic))
    try {

      var read = 0
      val numReads = numWrites.get
      while (read < numReads) {
        val records = consumer.poll(10000)
        if (records.isEmpty) throw new Exception("Consumer poll timeout")
        for (record <- records) {
          read += 1
          record.value.key.id should equal(record.key)
          record.value.text should equal(s"test value ${record.key}")
        }
      }
    } finally {
      consumer.close()
    }


  }
}