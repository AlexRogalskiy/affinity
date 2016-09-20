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

package io.amient.affinity.core.http

import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.avro.util.ByteBufferOutputStream

import scala.collection.JavaConverters._

object ResponseBuilder {

  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  def json(status: StatusCode, value: Any, gzip: Boolean = true) = {
    val out = new ByteBufferOutputStream()

    val writer = if (gzip) new OutputStreamWriter(new GZIPOutputStream(out)) else new OutputStreamWriter(out)
    mapper.writeValue(writer, value)

    //TODO - Source will always make the response chunked but for small non-gzip values this may be big overhead
    HttpResponse(status
      , entity = HttpEntity(ContentTypes.`application/json`, Source(out.getBufferList.asScala.map(ByteString(_)).toList))
      , headers = if (!gzip) List() else List(headers.`Content-Encoding`(HttpEncodings.gzip)))
  }

}