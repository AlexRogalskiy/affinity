package io.amient.affinity.kafka

import java.util

import com.typesafe.config.ConfigFactory
import io.amient.affinity.avro.{AvroRecord, AvroSerde}
import org.apache.avro.Schema
import org.apache.kafka.common.serialization.Serializer

class KafkaAvroSerializer extends Serializer[Any] {

  var isKey: Boolean = false
  var serde: AvroSerde = null

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {
    val config = ConfigFactory.parseMap(configs)
    this.serde = AvroSerde.create(config)
    this.isKey = isKey
  }

  override def serialize(topic: String, data: Any): Array[Byte] = {
    require(serde != null, "AvroSerde not configured")
    val subject = s"$topic-${if (isKey) "key" else "value"}"
    val (objSchema, schemaId) = serde.getOrRegisterSchema(data, subject)
    AvroRecord.write(data, objSchema, schemaId)
  }

  override def close(): Unit = if (serde != null) serde.close()
}