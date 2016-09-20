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

package io.amient.affinity.core.cluster

import java.util.concurrent.ConcurrentHashMap

import akka.routing.{ActorRefRoutee, Routee, RoutingLogic}
import io.amient.affinity.core.util.ObjectHashPartitioner

import scala.collection.immutable

case class DeterministicRoutingLogic(val numPartitions: Int) extends RoutingLogic {

  private var prevRoutees: immutable.IndexedSeq[Routee] = immutable.IndexedSeq()
  private val currentRouteMap = new ConcurrentHashMap[Int, Routee]()

  //TODO configurable partitioner
  val partitioner = new ObjectHashPartitioner

  def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee = {

    if (!prevRoutees.eq(routees)) {
      prevRoutees.synchronized {
          if (!prevRoutees.eq(routees)) {
            currentRouteMap.clear()
            routees.foreach {
              case actorRefRoutee: ActorRefRoutee =>
                /**
                  * relying on Region to assign partition name equal to physical partition id
                  */
                val partition = actorRefRoutee.ref.path.name.toInt
                currentRouteMap.put(partition, actorRefRoutee)
            }
            prevRoutees = routees
          }
      }
    }
    val p = message match {
      case (k, v) => partitioner.partition(k, numPartitions)
      case v => partitioner.partition(v, numPartitions)
    }

    /**
      * This means that no region has registered the partition - this may happen for 2 reasons:
      * 1. all regions representing that partition are genuinely down and there nothing that can be done
      * 2. between a master failure and a standby election there may be a brief period
      *    of the partition not being represented -
      */
    //TODO the 2. case needs to be handled by the API and there a different ways how to do it

    if (!currentRouteMap.containsKey(p)) {
      throw new IllegalStateException(s"Data partition `$p` is not represented in the cluster")
    }

    currentRouteMap.get(p)
  }

}